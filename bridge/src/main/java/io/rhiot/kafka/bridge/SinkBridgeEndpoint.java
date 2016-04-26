/**
 * Licensed to the Rhiot under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.rhiot.kafka.bridge;

import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.amqp.transport.ErrorCondition;
import org.apache.qpid.proton.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.proton.ProtonHelper;
import io.vertx.proton.ProtonLink;
import io.vertx.proton.ProtonQoS;
import io.vertx.proton.ProtonSender;

/**
 * Class in charge for reading from Apache Kafka
 * and bridging into AMQP traffic to receivers
 * 
 * @author ppatierno
 */
public class SinkBridgeEndpoint implements BridgeEndpoint {

	private static final Logger LOG = LoggerFactory.getLogger(SinkBridgeEndpoint.class);
	
	private static final String GROUP_ID_MATCH = "/group.id/";
	
	public static final String EVENT_BUS_SEND = "send";
	public static final String EVENT_BUS_ERROR = "error";
	public static final String EVENT_BUS_REQUEST_HEADER = "request";
	public static final String EVENT_BUS_ERROR_DESC_HEADER = "error-desc";
	public static final String EVENT_BUS_ERROR_AMQP_HEADER = "error-amqp";
	
	// Kafka consumer related stuff
	private KafkaConsumerWorker<String, byte[]> kafkaConsumerWorker;
	private Thread kafkaConsumerThread;
	
	// Event Bus communication stuff between Kafka consumer thread
	// and main Vert.x event loop
	private Vertx vertx;
	private String ebName;
	private MessageConsumer<String> ebConsumer;
	
	// converter from ConsumerRecord to AMQP message
	private MessageConverter<String, byte[]> converter;
	
	// used for tracking partitions and related offset for AT_LEAST_ONCE QoS delivery 
	private OffsetTracker<String, byte[]> offsetTracker;
	
	private Handler<BridgeEndpoint> closeHandler;
	
	private Queue<String> deliveryNotSent;
	
	/**
	 * Constructor
	 * @param vertx		Vert.x instance
	 */
	public SinkBridgeEndpoint(Vertx vertx) {
		this.vertx = vertx;
		this.converter = new DefaultMessageConverter();
		// generate an UUID as name for the Vert.x EventBus internal queue and shared local map
		this.ebName = String.format("%s.%s.%s", 
				Bridge.class.getSimpleName().toLowerCase(), 
				SinkBridgeEndpoint.class.getSimpleName().toLowerCase(), 
				UUID.randomUUID().toString());
		this.deliveryNotSent = new LinkedList<>();
		LOG.info("Event Bus queue and shared local map : {}", this.ebName);
	}
	
	@Override
	public void open() {
		
	}

	@Override
	public void close() {
		this.kafkaConsumerWorker.shutdown();
		if (this.ebConsumer != null)
			this.ebConsumer.unregister();
		
		this.deliveryNotSent.clear();
		this.vertx.sharedData().getLocalMap(this.ebName).clear();
		this.offsetTracker.clear();
	}
	
	@Override
	public void handle(ProtonLink<?> link) {
		
		if (!(link instanceof ProtonSender)) {
			throw new IllegalArgumentException("This Proton link must be a sender");
		}
		
		ProtonSender sender = (ProtonSender)link;
		sender.setSource(sender.getRemoteSource());
		
		// address is like this : [topic]/group.id/[group.id]
		String address = sender.getRemoteSource().getAddress();
		
		int groupIdIndex = address.indexOf(SinkBridgeEndpoint.GROUP_ID_MATCH);
		
		if (groupIdIndex == -1) {
		
			// group.id don't specified in the address, link will be closed
			LOG.info("Local detached");
			
			sender.setCondition(new ErrorCondition(Symbol.getSymbol(Bridge.AMQP_ERROR_NO_GROUPID), "Mandatory group.id not specified in the address"));
			sender.close();
			
			this.fireClose();
			
		} else {
		
			// group.id specified in the address, open sender and setup Kafka consumer
			sender
			.closeHandler(this::processCloseSender)
			.sendQueueDrainHandler(this::processSendQueueDrain)
			.open();
			
			String groupId = address.substring(groupIdIndex + SinkBridgeEndpoint.GROUP_ID_MATCH.length());
			String topic = address.substring(0, groupIdIndex);
			
			LOG.info("topic {} group.id {}", topic, groupId);
			
			this.offsetTracker = new SimpleOffsetTracker<>(topic);
			
			// get filters on partition and offset
			Source source = (Source) sender.getRemoteSource();
			Map<Symbol, Object> filters = source.getFilter();
			
			Object partition = null, offset = null;
			
			if (filters != null) {
				ErrorCondition condition = null;
				
				partition = filters.get(Symbol.getSymbol(Bridge.AMQP_PARTITION_FILTER));
				offset = filters.get(Symbol.getSymbol(Bridge.AMQP_OFFSET_FILTER));
				
				condition = this.checkFilters(partition, offset);
				
				if (condition != null) {
					sender.setCondition(condition);
					sender.close();
					
					this.fireClose();
					return;
				}
			}
					
			// creating configuration for Kafka consumer
			Properties props = new Properties();
			props.put(BridgeConfig.BOOTSTRAP_SERVERS, BridgeConfig.getBootstrapServers());
			props.put(BridgeConfig.KEY_DESERIALIZER, BridgeConfig.getKeyDeserializer());
			props.put(BridgeConfig.VALUE_DESERIALIZER, BridgeConfig.getValueDeserializer());
			props.put(BridgeConfig.GROUP_ID, groupId);
			props.put(BridgeConfig.ENABLE_AUTO_COMMIT, BridgeConfig.isEnableAutoCommit());
			props.put(BridgeConfig.AUTO_OFFSET_RESET, BridgeConfig.getAutoOffsetReset());
			
			// create and start new thread for reading from Kafka
			this.kafkaConsumerWorker = new KafkaConsumerWorker<>(props, 
					topic, (Integer)partition, (Long)offset, 
					this.vertx, this.ebName, 
					sender.getQoS(), this.offsetTracker);
			
			this.kafkaConsumerThread = new Thread(kafkaConsumerWorker);
			this.kafkaConsumerThread.start();
			
			// message sending on AMQP link MUST happen on Vert.x event loop due to
			// the access to the sender object provided by Vert.x handler
			// (we MUST avoid to access it from other threads; i.e. Kafka consumer thread)
			this.ebConsumer = this.vertx.eventBus().consumer(this.ebName, ebMessage -> {
				
				switch (ebMessage.headers().get(SinkBridgeEndpoint.EVENT_BUS_REQUEST_HEADER)) {
					
					case SinkBridgeEndpoint.EVENT_BUS_SEND:
						
						if (!sender.sendQueueFull()) {
							
							// the remote receiver has credits, we can send the message
						
							ConsumerRecord<String, byte[]> record = null;
							
							if (sender.getQoS() == ProtonQoS.AT_MOST_ONCE) {
								
								// Sender QoS settled (AT_MOST_ONCE)
								
								String deliveryTag = ebMessage.body();
								
								Object obj = this.vertx.sharedData().getLocalMap(this.ebName).remove(deliveryTag);
								
								if (obj instanceof KafkaMessage<?, ?>) {
									
									KafkaMessage<String, byte[]> kafkaMessage = (KafkaMessage<String, byte[]>) obj;
									record = kafkaMessage.getRecord();
									
									Message message = converter.toAmqpMessage(record);
									sender.send(ProtonHelper.tag(String.valueOf(deliveryTag)), message);
								}
								
							} else {
								
								// Sender QoS unsettled (AT_LEAST_ONCE)
									
								String deliveryTag = ebMessage.body();
								
								Object obj = this.vertx.sharedData().getLocalMap(this.ebName).remove(deliveryTag);
								
								if (obj instanceof KafkaMessage<?, ?>) {
	
									KafkaMessage<String, byte[]> kafkaMessage = (KafkaMessage<String, byte[]>) obj;
									record = kafkaMessage.getRecord();
									
									Message message = converter.toAmqpMessage(record);
									
									// record (converted in AMQP message) is on the way ... ask to tracker to track its delivery
									this.offsetTracker.track(deliveryTag, record);
									
									LOG.info("Tracked {} - {} [{}]", record.topic(), record.partition(), record.offset());
									
									sender.send(ProtonHelper.tag(deliveryTag), message, delivery -> {
										
										// a record (converted in AMQP message) is delivered ... communicate it to the tracker
										String tag = new String(delivery.getTag());
										this.offsetTracker.delivered(tag);
										
										LOG.info("Message tag {} delivered {} to {}", tag, delivery.getRemoteState(), sender.getSource().getAddress());
									});
								}
								
							}			
						} else {
							
							// no credits available on receiver side, save the current deliveryTag and pause the Kafka consumer
							this.deliveryNotSent.add(ebMessage.body());
							this.kafkaConsumerWorker.pause();
						}
						break;
					
					case SinkBridgeEndpoint.EVENT_BUS_ERROR:
						
						LOG.info("Local detached");
						
						// no partitions assigned, the AMQP link and Kafka consumer will be closed
						sender.setCondition(
								new ErrorCondition(Symbol.getSymbol(ebMessage.headers().get(SinkBridgeEndpoint.EVENT_BUS_ERROR_AMQP_HEADER)), 
								ebMessage.headers().get(SinkBridgeEndpoint.EVENT_BUS_ERROR_DESC_HEADER)));
						sender.close();
						
						this.close();
						this.fireClose();
						break;
				}
				
			});
		}
	}
	
	/**
	 * Handle for detached link by the remote receiver
	 * @param ar		async result with info on related Proton sender
	 */
	private void processCloseSender(AsyncResult<ProtonSender> ar) {
		
		if (ar.succeeded()) {
			
			LOG.info("Remote detached");
			
			ar.result().close();
			
			this.close();
			this.fireClose();
		}
	}
	
	/**
	 * Handle for flow control on the link when sender receives credits to send
	 * @param sender
	 */
	private void processSendQueueDrain(ProtonSender sender) {
		
		LOG.info("Remote receiver link credits available");
		
		String deliveryTag;
		
		DeliveryOptions options = new DeliveryOptions();
		options.addHeader(SinkBridgeEndpoint.EVENT_BUS_REQUEST_HEADER, SinkBridgeEndpoint.EVENT_BUS_SEND);
		
		// before resuming Kafka consumer, we need to send cached delivery
		while ((deliveryTag = this.deliveryNotSent.peek()) != null) {
			
			if (!sender.sendQueueFull()) {
				
				LOG.info("Recovering not sent delivery ... {}", deliveryTag);
				this.deliveryNotSent.remove();
				this.vertx.eventBus().send(this.ebName, deliveryTag, options);
				
			} else {
				
				return;
			}
		}
		
		if (this.kafkaConsumerWorker != null)
			this.kafkaConsumerWorker.resume();
	}
	
	@Override
	public BridgeEndpoint closeHandler(Handler<BridgeEndpoint> endpointCloseHandler) {
		this.closeHandler = endpointCloseHandler;
		return this;
	}
	
	/**
	 * Raise close event
	 */
	private void fireClose() {
		if (this.closeHandler != null) {
			this.closeHandler.handle(this);
		}
	}
	
	/**
	 * Check filters validity on partition and offset
	 * 
	 * @param partition		Partition
	 * @param offset		Offset
	 * @return				ErrorCondition related to a wrong filter
	 */
	private ErrorCondition checkFilters(Object partition, Object offset) {
		
		ErrorCondition condition = null;
		
		if (partition != null && !(partition instanceof Integer)) {
			// wrong type for partition value
			condition = new ErrorCondition(Symbol.getSymbol(Bridge.AMQP_ERROR_WRONG_PARTITION_FILTER), "Wrong partition filter");
			return condition;
		}
		
		if (offset != null && !(offset instanceof Long)) {
			// wrong type for offset value
			condition = new ErrorCondition(Symbol.getSymbol(Bridge.AMQP_ERROR_WRONG_OFFSET_FILTER), "Wrong offset filter");
			return condition;
		}
		
		if (partition == null && offset != null) {
			// no meaning only offset without partition
			condition = new ErrorCondition(Symbol.getSymbol(Bridge.AMQP_ERROR_NO_PARTITION_FILTER), "No partition filter specied");
			return condition;
		}
		
		if (partition != null && (Integer)partition < 0) {
			// no negative partition value allowed
			condition = new ErrorCondition(Symbol.getSymbol(Bridge.AMQP_ERROR_WRONG_FILTER), "Wrong filter");
			return condition;
		}
		
		if (offset != null && (Long)offset < 0) {
			// no negative offset value allowed
			condition = new ErrorCondition(Symbol.getSymbol(Bridge.AMQP_ERROR_WRONG_FILTER), "Wrong filter");
			return condition;
		}
		
		return condition;
	}
}