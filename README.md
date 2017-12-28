Tachikoma ESP [![Build Status](https://travis-ci.org/SourceForgery/tachikoma.svg?branch=master)](https://travis-ci.org/SourceForgery/tachikoma)
=============

This will be an Email Service Provider Software suitable for use with large amounts of transactional
emails.

Primary features
* Handle relatively large amount of emails (sub 100k/month)
* Accurately track bounce, delivers, opens & clicks
* Handle unsubscribe properly via replacing links in the email and
  [RFC8058](https://tools.ietf.org/html/rfc8058)
* Block lists for unsubscribed emails (per sender email)
* Zero Downtime upgrades for web server
* No messages lost, not even during upgrade


Possible later improvements:
* Queue outgoing emails until a specific time
* Web API 
* Template support


**Runtime requirements**

Once finished it will primarily be distributed as one or more docker images, but it's nowhere
near there yet. This means that all servers must be installed and configured locally (or inside a
docker with port forward).

What it uses:
* Kotlin language (JVM and JS)
* PostgreSQL database
* Postfix email server
* RabbitMQ message broker
* Rust language
* gRPC API

**Setting up the development environment**

Setting up the different programs necessary to develop (not run)
it in IntelliJ.

* [Rustup](https://www.rustup.rs/) is necessary for IntelliJ and an
  easy way to get rust build env up. Run as user, *not* root
* [gRPC Compiler (protoc) 3.x](https://developers.google.com/protocol-buffers/docs/downloads). Also
  available via ```apt install protobuf-compiler```
* JDK 8 (JRE is untested)
* Plugin for Rust

Getting around IntelliJ quirk:
1. Build with ```./gradlew build``` in the root (should build cleanly).
2. Manually mark ```tachikoma-backend-api-proto/tachikoma-backend-api-jvm/build/generated/source/proto/main/java```
   as Generated source root in IntelliJ. (```Mark Directory as``` in the context menu)