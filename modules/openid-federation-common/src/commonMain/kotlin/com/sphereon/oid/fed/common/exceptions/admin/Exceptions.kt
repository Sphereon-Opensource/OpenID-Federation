package com.sphereon.oid.fed.common.exceptions.admin

open class ApplicationException(message: String) : RuntimeException(message)

class BadRequestException(message: String) : ApplicationException(message)
class EntityAlreadyExistsException(message: String) : ApplicationException(message)
class NotFoundException(message: String) : ApplicationException(message)

