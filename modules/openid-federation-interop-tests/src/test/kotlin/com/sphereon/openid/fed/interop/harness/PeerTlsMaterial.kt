package com.sphereon.openid.fed.interop.harness

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import java.io.StringWriter
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date

internal data class PeerTlsMaterial(val caPem: String, val leafPem: String, val keyPem: String)

internal object PeerTlsCertificateFactory {
    fun create(vararg hosts: String): PeerTlsMaterial {
        require(hosts.isNotEmpty()) { "at least one TLS host is required" }
        require(hosts.all { it.matches(Regex("[A-Za-z0-9.-]+")) }) { "TLS hosts must be DNS names" }
        val caKeys = keyPair()
        val leafKeys = keyPair()
        val now = Instant.now()
        val caName = X500Name("CN=Interop Test CA")
        val leafName = X500Name("CN=${hosts.first()}")
        val ca = certificate(caName, caName, caKeys, caKeys, now, true, null)
        val leaf = certificate(leafName, caName, leafKeys, caKeys, now, false, hosts.toList())
        ca.checkValidity(Date())
        leaf.verify(ca.publicKey)
        return PeerTlsMaterial(pem(ca), pem(leaf), pem(leafKeys.private))
    }

    private fun keyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun certificate(
        subject: X500Name,
        issuer: X500Name,
        subjectKeys: KeyPair,
        issuerKeys: KeyPair,
        now: Instant,
        isCa: Boolean,
        sanHosts: List<String>?,
    ): X509Certificate {
        val from = Date.from(now.minusSeconds(60))
        val to = Date.from(now.plusSeconds(7 * 24 * 60 * 60L))
        val serial = BigInteger(160, SecureRandom()).abs().max(BigInteger.ONE)
        val builder = JcaX509v3CertificateBuilder(issuer, serial, from, to, subject, subjectKeys.public)
            .addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
            .addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(if (isCa) KeyUsage.keyCertSign or KeyUsage.cRLSign else KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
        if (sanHosts != null) {
            val names = sanHosts.map { GeneralName(GeneralName.dNSName, it) }.toTypedArray()
            builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names))
        }
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun pem(value: Any): String = StringWriter().use { writer -> JcaPEMWriter(writer).use { it.writeObject(value) }; writer.toString() }
}
