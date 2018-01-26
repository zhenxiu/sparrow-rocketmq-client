package com.sparrow.rocketmq.impl;

import com.sparrow.cache.CacheClient;
import com.sparrow.constant.cache.KEY;
import com.sparrow.exception.CacheConnectionException;
import com.sparrow.mq.MQContainerProvider;
import com.sparrow.mq.MQEvent;
import com.sparrow.mq.MQHandler;
import com.sparrow.mq.MQ_CLIENT;
import com.sparrow.mq.QueueHandlerMappingContainer;
import com.sparrow.rocketmq.MessageConverter;
import com.sparrow.support.redis.impl.RedisDistributedCountDownLatch;
import com.sparrow.utility.StringUtility;
import java.util.List;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by harry on 2017/6/14.
 */
public class SparrowRocketMQMessageListener implements MessageListenerConcurrently {

    public SparrowRocketMQMessageListener(){
        System.out.println("init spring rocket mq message listener");
    }
    private static Logger logger = LoggerFactory.getLogger(SparrowRocketMQMessageListener.class);

    private QueueHandlerMappingContainer queueHandlerMappingContainer = MQContainerProvider.getContainer();
    private MessageConverter messageConverter;
    private CacheClient cacheClient;

    public void setQueueHandlerMappingContainer(QueueHandlerMappingContainer queueHandlerMappingContainer) {
        this.queueHandlerMappingContainer = queueHandlerMappingContainer;
    }

    public void setMessageConverter(MessageConverter messageConverter) {
        this.messageConverter = messageConverter;
    }

    protected boolean before(MQEvent event,KEY monitor, String keys) {
        logger.info("starting sparrow consume {},monitor {}, keys {}...",event,monitor,keys);
        if (monitor==null) {
            return true;
        }
        try {
            return !cacheClient.set().exist(monitor,keys);
        } catch (CacheConnectionException e) {
            return true;
        }
    }

    protected void after(MQEvent event,KEY monitor,String keys) {
        logger.info("ending sparrow consume {},monitor {},keys {} ...",event,monitor,keys);
        if (StringUtility.isNullOrEmpty(monitor)) {
            return;
        }
        RedisDistributedCountDownLatch redisDistributedCountDownLatch = new RedisDistributedCountDownLatch(cacheClient, monitor);
        redisDistributedCountDownLatch.consume(keys);
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> list, ConsumeConcurrentlyContext context) {
        MessageExt message = list.get(0);
        String type = message.getProperties().get(MQ_CLIENT.CLASS_NAME);
        try {
            if (logger.isInfoEnabled()) {
                logger.info("receive msg:" + message.toString());
            }
            MQHandler handler = queueHandlerMappingContainer.get(type);
            try {
                MQEvent event = messageConverter.fromMessage(message);
                KEY monitor=KEY.parse(message.getProperties().get(MQ_CLIENT.MONITOR_KEY));
                if(!this.before(event,monitor,message.getKeys())){
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                }
                handler.handle(event);
                this.after(event,monitor,message.getKeys());
            } catch (Throwable e) {
                logger.error("message error", e);
            }
        } catch (Throwable e) {
            logger.error("process failed, msg : " + message, e);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }
}