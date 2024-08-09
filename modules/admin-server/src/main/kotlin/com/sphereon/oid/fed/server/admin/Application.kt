package com.sphereon.oid.fed.server.admin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan

@SpringBootApplication
@ComponentScan(basePackages = ["com.sphereon.oid.fed.services"])
class Application

fun main(args: Array<String>) {
	runApplication<Application>(*args)
}