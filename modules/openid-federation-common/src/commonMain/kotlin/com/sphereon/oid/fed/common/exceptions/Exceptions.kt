package com.sphereon.oid.fed.common.exceptions

open class ApplicationException(message: String) : RuntimeException(message)

class NotFoundException(message: String) : ApplicationException(message)
class EntityAlreadyExistsException(message: String) : ApplicationException(message)
