package model

import io.circe.{Codec, Decoder, Encoder}

case class DeviceEvent(timestamp: String, device_ip: String, error_code: Int) derives Codec.AsObject
