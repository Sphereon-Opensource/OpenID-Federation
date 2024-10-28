package com.sphereon.oid.fed.client.types

interface ICallbackService<PlatformCallbackType> {
    fun register(platformCallback: PlatformCallbackType?): ICallbackService<PlatformCallbackType>
}
