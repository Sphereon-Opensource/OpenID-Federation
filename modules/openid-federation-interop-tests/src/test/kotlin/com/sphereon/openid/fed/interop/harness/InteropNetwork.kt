package com.sphereon.openid.fed.interop.harness

import org.testcontainers.containers.Network as TcNetwork
import com.github.dockerjava.api.model.Network.Ipam as DockerNetworkIpam
import com.github.dockerjava.api.model.Network.Ipam.Config as DockerNetworkIpamConfig

/** Creates one Testcontainers-owned bridge, optionally on an explicitly selected private /24. */
internal object InteropNetwork {
    private const val SUBNET_PROPERTY = "interop.network.subnet"

    fun create(): TcNetwork {
        val subnet = System.getProperty(SUBNET_PROPERTY) ?: return TcNetwork.newNetwork()
        val cidr = subnet.split('/')
        require(cidr.size == 2 && cidr[1] == "24") {
            "-P$SUBNET_PROPERTY must be an RFC1918 IPv4 /24 subnet, for example 10.253.140.0/24"
        }
        val addressOctets = cidr[0].split('.')
        require(addressOctets.size == 4) { "-P$SUBNET_PROPERTY must be an IPv4 /24 subnet" }
        val octets = addressOctets.map { octet ->
            octet.toIntOrNull()?.takeIf { it in 0..255 && it.toString() == octet }
                ?: throw IllegalArgumentException("-P$SUBNET_PROPERTY must contain a canonical IPv4 address")
        }
        require(octets.last() == 0) {
            "-P$SUBNET_PROPERTY must name the canonical network address of its /24 subnet"
        }
        val privateAddress = octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
        require(privateAddress) { "-P$SUBNET_PROPERTY must use an RFC1918 private address range" }

        return TcNetwork.builder()
            .createNetworkCmdModifier { command ->
                command.withIpam(
                    DockerNetworkIpam().withConfig(
                        DockerNetworkIpamConfig().withSubnet(subnet),
                    ),
                )
            }
            .build()
    }
}
