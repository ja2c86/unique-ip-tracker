package model

case class Config(mongoConfig: MongoConfig, kafkaConfig: KafkaConfig)

case class MongoConfig(connectionString: String, database: String, collection: String)

case class KafkaConfig(bootstrapServers: String, topic: String, groupId: String)
