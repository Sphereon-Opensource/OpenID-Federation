package com.sphereon.oid.fed.kms.local

import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Repository

@Repository
interface KeyRepository : CrudRepository<Key, String> {

    @Query("SELECT * FROM keys WHERE id = :keyId")
    fun findByKeyId(keyId: String): Key?
}