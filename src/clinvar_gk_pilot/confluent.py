"""
Writes ndjson to a confluent cloud topic.
"""
from confluent_kafka import Producer, Consumer, TopicPartition
import os
import socket
import gk_pilot
import json


producer_conf = {'bootstrap.servers': os.getenv('CCLOUD_BROKERS'),       #'pkc-abcd85.us-west-2.aws.confluent.cloud:9092',
        'security.protocol': 'SASL_SSL',
        'sasl.mechanism': 'PLAIN',
        'sasl.username': 'IJVUJZI3NM2CUYDT', # os.getenv('CCLOUD_ACCESS_KEY_ID'),     #'<CLUSTER_API_KEY>',
        'sasl.password': 'nf4M8VLWPXuIbXRhaqDlTK0wphzLxxuN99wQ5UE9uxgHVK7q8h6kRo0q2HLKdnBq', # os.getenv('CCLOUD_SECRET_ACCESS_KEY'), #'<CLUSTER_API_SECRET>',
        'client.id': socket.gethostname(),
        'auto.offset.reset': 'earliest'}
consumer_conf = producer_conf.copy()
consumer_conf['group.id'] = 'clinvar-gk-pilot'

def write_statements_to_topic(producer: Producer, topic: str) -> None:
    for k, v in gk_pilot.get_all_statements().items():
        producer.produce(topic, key=k, value=json.dumps(v))
    producer.poll(10000)
    producer.flush()


if __name__ == '__main__':

    producer = Producer(producer_conf)
    #producer.produce("terry-test", key="key", value="value")
    #producer.flush()
    write_statements_to_topic(producer, "clinvar-gk-pilot")

    # print(consumer_conf)
    # consumer = Consumer(consumer_conf)
    # consumer.subscribe(["terry-test"])
    # msg = consumer.poll(timeout=1.0)
    # print(msg.key())