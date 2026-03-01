package com.sphereon.openid.fed.services.mappers

import com.sphereon.openid.fed.openapi.models.Account
import com.sphereon.openid.fed.openapi.models.AccountsResponse
import com.sphereon.openid.fed.persistence.models.Account as AccountEntity

/**
 * Backward compatibility re-exports.
 * Use functions from [com.sphereon.openid.fed.account.mappers] instead.
 */

fun AccountEntity.toDTO(): Account = com.sphereon.openid.fed.account.mappers.toDTO(this)

fun List<Account>.toAccountsResponse(): AccountsResponse = com.sphereon.openid.fed.account.mappers.toAccountsResponse(this)
