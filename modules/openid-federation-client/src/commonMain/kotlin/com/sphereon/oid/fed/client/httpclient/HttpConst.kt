package com.sphereon.oid.fed.client.httpclient

import com.sphereon.oid.fed.common.logging.Logger

object HttpConst {
    val LOG_NAMESPACE = "sphereon:kmp:openid-federation-client"
    val LOG = Logger.Static.tag(LOG_NAMESPACE)
    val JWT_LITERAL = "HTTP"
}
