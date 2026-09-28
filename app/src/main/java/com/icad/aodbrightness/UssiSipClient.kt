package com.icad.aodbrightness

import android.content.Context
import android.net.ConnectivityManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Base64
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

object UssiSipClient {
    private const val TAG = "UssiSipClient"

    private const val DEFAULT_PCSCF_IP = "2400:9800:2:1c00::134"
    private const val PCSCF_PORT = 5060
    private const val DOMAIN = "ims.mnc011.mcc510.3gppnetwork.org"

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun calculateSimAka(classLoader: ClassLoader?, nonce: String): String? {
        if (classLoader == null || nonce.isBlank()) return null
        try {
            val rawNonce = runCatching { Base64.decode(nonce, Base64.DEFAULT) }.getOrNull() ?: return null
            Log.i(TAG, "[USSI v5] Nonce decoded bytes: ${rawNonce.size}")

            // 3GPP TS 31.102 Clause 7.1.2:
            // Input for AUTHENTICATE is: [len(RAND)] + RAND + [len(AUTN)] + AUTN (34 bytes total)
            val formattedChallenge = if (rawNonce.size == 32) {
                val tlv = ByteArray(34)
                tlv[0] = 16
                System.arraycopy(rawNonce, 0, tlv, 1, 16)
                tlv[17] = 16
                System.arraycopy(rawNonce, 16, tlv, 18, 16)
                Base64.encodeToString(tlv, Base64.NO_WRAP)
            } else {
                nonce
            }
            Log.i(TAG, "[USSI v5] Formatted TS 31.102 challenge: $formattedChallenge")

            val uiccCtrlClass = classLoader.loadClass("com.android.internal.telephony.uicc.UiccController")
            val getInstance = uiccCtrlClass.getMethod("getInstance")
            val uiccCtrl = getInstance.invoke(null) ?: return null
            val getApp = uiccCtrlClass.getMethod("getUiccCardApplication", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)

            // Try with formatted challenge first, then raw nonce
            for (challengeToTry in listOf(formattedChallenge, nonce)) {
                for (phoneId in listOf(1, 0)) {
                    for (family in listOf(1, 3)) {
                        val app = getApp.invoke(uiccCtrl, phoneId, family) ?: continue
                        val getIccRecords = app.javaClass.getMethod("getIccRecords")
                        val records = getIccRecords.invoke(app) ?: continue
                        val getChallenge = records.javaClass.getMethod("getIccSimChallengeResponse", Int::class.javaPrimitiveType, String::class.java)
                        val resp = getChallenge.invoke(records, 129 /* AUTH_CONTEXT_EAP_AKA */, challengeToTry) as? String
                        Log.i(TAG, "[USSI v5] UiccController getIccSimChallengeResponse(phoneId=$phoneId, family=$family) -> $resp")
                        if (!resp.isNullOrBlank()) {
                            return resp
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[USSI v5] calculateSimAka error: ${t.message}", t)
        }
        return null
    }

    fun queryUssd(ussdCode: String, context: Context? = null, classLoader: ClassLoader? = null): String? {
        var socket: DatagramSocket? = null
        try {
            Log.i(TAG, "Starting 3GPP TS 24.390 USSI session for $ussdCode...")

            var selectedNif: NetworkInterface? = null
            var localIp: Inet6Address? = null

            for (nif in NetworkInterface.getNetworkInterfaces().toList()) {
                if (nif.name.startsWith("rmnet_data") && nif.isUp) {
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet6Address && !addr.isLinkLocalAddress && !addr.isLoopbackAddress) {
                            selectedNif = nif
                            localIp = addr
                            break
                        }
                    }
                    if (localIp != null) break
                }
            }

            if (selectedNif == null || localIp == null) {
                Log.e(TAG, "No active rmnet_data interface with global IPv6 found!")
                return "Error: No active IMS cellular bearer"
            }

            val ifaceName = selectedNif.name
            val localIpStr = localIp.hostAddress?.substringBefore("%") ?: ""
            Log.i(TAG, "Using interface $ifaceName with local IPv6: $localIpStr")

            // Ensure policy routing rule exists so IMS packets route via cellular bearer
            try {
                Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c", "ip -6 rule add pref 500 to 2400:9800::/32 lookup $ifaceName")).waitFor()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed setting routing rule: ${t.message}")
            }

            // Detect P-CSCF, subId, IMSI, and subscriber phone number
            var pcscfIp = DEFAULT_PCSCF_IP
            var msisdn = "+628179322550"
            var imsi = "510118101990303"
            var subId = 1

            if (context != null) {
                runCatching {
                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    val linkProps = cm?.allNetworks?.mapNotNull { cm.getLinkProperties(it) }
                        ?.firstOrNull { it.interfaceName == ifaceName }
                    val pcscfMethod = linkProps?.javaClass?.methods?.firstOrNull { it.name.contains("Pcscf", ignoreCase = true) }
                    val pcscfList = pcscfMethod?.invoke(linkProps) as? List<*>
                    val foundPcscf = pcscfList?.firstOrNull()?.toString()?.removePrefix("/")?.substringBefore("%")
                    if (!foundPcscf.isNullOrBlank()) {
                        pcscfIp = foundPcscf
                        Log.i(TAG, "Discovered dynamic P-CSCF: $pcscfIp")
                    }
                }
                runCatching {
                    val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
                    subId = sm?.activeSubscriptionInfoList?.firstOrNull()?.subscriptionId ?: 1
                }
                runCatching {
                    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    tm?.subscriberId?.let { if (it.isNotBlank()) imsi = it }
                    tm?.line1Number?.let { if (it.isNotBlank()) msisdn = it }
                }
            }

            // Detect IMEI / instance-id for Multi-UE registration
            var imei = ""
            if (classLoader != null) {
                runCatching {
                    val phoneFactoryClass = classLoader.loadClass("com.android.internal.telephony.PhoneFactory")
                    val getPhones = phoneFactoryClass.getMethod("getPhones")
                    val phones = getPhones.invoke(null) as? Array<*>
                    for (phone in phones ?: emptyArray<Any?>()) {
                        val getImeiMethod = phone?.javaClass?.methods?.firstOrNull { it.name == "getImei" && it.parameterTypes.isEmpty() }
                            ?: phone?.javaClass?.methods?.firstOrNull { it.name == "getDeviceId" && it.parameterTypes.isEmpty() }
                        val foundImei = getImeiMethod?.invoke(phone) as? String
                        if (!foundImei.isNullOrBlank()) {
                            imei = foundImei
                            break
                        }
                    }
                }
            }
            if (imei.isBlank() && context != null) {
                runCatching {
                    val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    imei = tm?.imei ?: tm?.deviceId ?: ""
                }
            }
            if (imei.isBlank()) {
                imei = "35" + imsi.takeLast(12) + "0"
            }
            val cleanImei = imei.filter { it.isDigit() }.padEnd(15, '0')
            val tac = cleanImei.substring(0, 8)
            val snr = cleanImei.substring(8, 14)
            val cd = cleanImei.substring(14, 15)
            val instanceId = "<urn:gsma:imei:$tac-$snr-$cd>"
            Log.i(TAG, "[USSI v5] Using +sip.instance: $instanceId (IMEI: $cleanImei)")

            val contactParams = ";+sip.instance=\"$instanceId\";+g.3gpp.icsi-ref=\"urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel\""

            val impi = "$imsi@$DOMAIN"
            val impu = "sip:$msisdn@$DOMAIN"

            socket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 4500
                bind(InetSocketAddress(localIp, 0))
            }

            val localPort = socket.localPort
            val pcscfAddr = Inet6Address.getByName(pcscfIp)
            val regCallId = "reg-${UUID.randomUUID()}@[$localIpStr]"
            val regFromTag = "reg-${System.currentTimeMillis()}"

            // ── STEP 1: INITIAL SIP REGISTER ─────────────────────────────────────────
            var regExpires = 3600
            var cseq = 1

            fun makeInitialRegister(exp: Int, seq: Int): String =
                "REGISTER sip:$DOMAIN SIP/2.0\r\n" +
                "Via: SIP/2.0/UDP [$localIpStr]:$localPort;branch=z9hG4bK-${UUID.randomUUID()}\r\n" +
                "Max-Forwards: 70\r\n" +
                "Route: <sip:[$pcscfIp]:$PCSCF_PORT;lr>\r\n" +
                "From: <$impu>;tag=$regFromTag\r\n" +
                "To: <$impu>\r\n" +
                "Call-ID: $regCallId\r\n" +
                "CSeq: $seq REGISTER\r\n" +
                "Contact: <sip:$msisdn@[$localIpStr]:$localPort>$contactParams;expires=$exp\r\n" +
                "P-Visited-Network-ID: \"$DOMAIN\"\r\n" +
                "P-Access-Network-Info: 3GPP-E-UTRAN-FDD; utran-cell-id-3gpp=510110000000000\r\n" +
                "User-Agent: IM-client/OMA1.0 Sony-Xperia-10-III/1.0\r\n" +
                "Authorization: Digest username=\"$impi\", realm=\"$DOMAIN\", nonce=\"\", uri=\"sip:$DOMAIN\", response=\"\"\r\n" +
                "Supported: path, sec-agree\r\n" +
                "Expires: $exp\r\n" +
                "Content-Length: 0\r\n\r\n"

            var initialRegister = makeInitialRegister(regExpires, cseq)

            Log.i(TAG, "Sending Initial SIP REGISTER (cseq=$cseq, expires=$regExpires) to [$pcscfIp]:$PCSCF_PORT...")
            socket.send(DatagramPacket(initialRegister.toByteArray(Charsets.UTF_8), initialRegister.length, pcscfAddr, PCSCF_PORT))

            val buf = ByteArray(8192)
            val regPacket1 = DatagramPacket(buf, buf.size)
            socket.receive(regPacket1)
            var regResp1 = String(regPacket1.data, 0, regPacket1.length)
            Log.i(TAG, "Received REGISTER response #1:\n$regResp1")

            // If 100 Trying, receive again
            if (regResp1.contains("100 Trying")) {
                val nextPacket = DatagramPacket(buf, buf.size)
                socket.receive(nextPacket)
                regResp1 = String(nextPacket.data, 0, nextPacket.length)
                Log.i(TAG, "Received REGISTER response #2:\n$regResp1")
            }

            // Handle 423 Interval Too Brief
            if (regResp1.contains("423 Interval Too Brief")) {
                val minExp = regResp1.lines().firstOrNull { it.startsWith("Min-Expires:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 3600
                regExpires = maxOf(regExpires, minExp)
                cseq++
                Log.i(TAG, "Got 423 Interval Too Brief! Retrying initial REGISTER with cseq=$cseq, expires=$regExpires...")
                val retryReg = makeInitialRegister(regExpires, cseq)
                socket.send(DatagramPacket(retryReg.toByteArray(Charsets.UTF_8), retryReg.length, pcscfAddr, PCSCF_PORT))
                socket.receive(regPacket1)
                regResp1 = String(regPacket1.data, 0, regPacket1.length)
                if (regResp1.contains("100 Trying")) {
                    socket.receive(regPacket1)
                    regResp1 = String(regPacket1.data, 0, regPacket1.length)
                }
                Log.i(TAG, "Received REGISTER response after retry:\n$regResp1")
            }

            if (!regResp1.contains("401 Unauthorized")) {
                Log.w(TAG, "Expected 401 Unauthorized but received: ${regResp1.lines().firstOrNull()}")
            }

            // Parse WWW-Authenticate header
            val authLine = regResp1.lines().firstOrNull { it.startsWith("WWW-Authenticate:", ignoreCase = true) }
                ?: regResp1.substringAfter("WWW-Authenticate:", "").substringBefore("\r\n")

            val nonce = authLine.substringAfter("nonce=\"", "").substringBefore("\"")
            val realm = authLine.substringAfter("realm=\"", "").substringBefore("\"").ifEmpty { DOMAIN }
            val hasQop = authLine.contains("qop=", ignoreCase = true)
            val qopParam = if (hasQop) authLine.substringAfter("qop=\"", "").substringBefore("\"").ifEmpty { "auth" } else ""
            val opaque = authLine.substringAfter("opaque=\"", "").substringBefore("\"")

            Log.i(TAG, "[USSI v5] Parsed challenge: realm=$realm, nonce=$nonce, hasQop=$hasQop, qopParam=$qopParam, opaque=$opaque")

            // ── STEP 2: UICC SIM AKA CHALLENGE RESPONSE ─────────────────────────────
            var authHeader = ""
            var ha1Raw = ""
            var ha1HexStr = ""
            var wasAutsSent = false

            if (nonce.isNotEmpty()) {
                var rawAkaResp: String? = calculateSimAka(classLoader, nonce)
                if (rawAkaResp.isNullOrEmpty() && context != null) {
                    try {
                        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                        val tmSub = if (subId > 0) tm.createForSubscriptionId(subId) else tm
                        rawAkaResp = tmSub.getIccAuthentication(2 /* APPTYPE_USIM */, 129 /* AUTHTYPE_EAP_AKA */, nonce)
                            ?: runCatching {
                                tm.javaClass.getMethod("getIccAuthentication", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                                    .invoke(tm, subId, 2, 129, nonce) as? String
                            }.getOrNull()
                    } catch (t: Throwable) {
                        Log.e(TAG, "[USSI v5] getIccAuthentication error: ${t.message}", t)
                    }
                }
                Log.i(TAG, "[USSI v5] Final SIM AKA challenge response: $rawAkaResp")

                if (!rawAkaResp.isNullOrEmpty()) {
                    val akaBytes = Base64.decode(rawAkaResp, Base64.DEFAULT)
                    if (akaBytes.isNotEmpty() && akaBytes[0] == 0xDB.toByte()) {
                        // Success tag (0xDB), byte 1 is RES length
                        val resLen = akaBytes[1].toInt() and 0xFF
                        val resBytes = akaBytes.copyOfRange(2, 2 + resLen)
                        val resHex = bytesToHex(resBytes)
                        Log.i(TAG, "[USSI v5] Derived RES (${resLen} bytes): $resHex")

                        // RFC 3310: HA1 = MD5(username ":" realm ":" password)
                        // password is the raw RES bytes
                        val mdRaw = MessageDigest.getInstance("MD5")
                        mdRaw.update("$impi:$realm:".toByteArray(Charsets.UTF_8))
                        mdRaw.update(resBytes)
                        ha1Raw = mdRaw.digest().joinToString("") { "%02x".format(it) }

                        ha1HexStr = md5Hex("$impi:$realm:$resHex")
                        Log.i(TAG, "[USSI v5] HA1 (raw bytes): $ha1Raw, HA1 (hex string): $ha1HexStr")

                        // Primary choice: ha1Raw as specified by RFC 3310 / 3GPP TS 33.203
                        val ha1 = ha1Raw
                        val ha2 = md5Hex("REGISTER:sip:$DOMAIN")

                        val responseDigest: String
                        val authBuilder = StringBuilder("Authorization: Digest ")
                        authBuilder.append("username=\"$impi\", realm=\"$realm\", nonce=\"$nonce\", uri=\"sip:$DOMAIN\"")

                        if (hasQop && qopParam.isNotEmpty()) {
                            val cnonce = UUID.randomUUID().toString().substring(0, 8)
                            val nc = "00000001"
                            responseDigest = md5Hex("$ha1:$nonce:$nc:$cnonce:$qopParam:$ha2")
                            authBuilder.append(", qop=$qopParam, nc=$nc, cnonce=\"$cnonce\"")
                        } else {
                            // Standard RFC 2617 without qop: response = MD5(HA1:nonce:HA2)
                            responseDigest = md5Hex("$ha1:$nonce:$ha2")
                        }

                        authBuilder.append(", response=\"$responseDigest\", algorithm=AKAv1-MD5")
                        if (opaque.isNotEmpty()) {
                            authBuilder.append(", opaque=\"$opaque\"")
                        }
                        authHeader = authBuilder.toString()
                        Log.i(TAG, "[USSI v5] Constructed Auth Header: $authHeader")
                    } else if (akaBytes.isNotEmpty() && akaBytes[0] == 0xDC.toByte()) {
                        // Synchronization failure tag (0xDC)
                        val autsLen = akaBytes[1].toInt() and 0xFF
                        val autsBytes = akaBytes.copyOfRange(2, 2 + autsLen)
                        val autsBase64 = Base64.encodeToString(autsBytes, Base64.NO_WRAP)
                        Log.i(TAG, "[USSI v5] Synchronization failure, sending AUTS: $autsBase64")

                        val ha1 = md5Hex("$impi:$realm:")
                        val ha2 = md5Hex("REGISTER:sip:$DOMAIN")
                        val responseDigest = md5Hex("$ha1:$nonce:$ha2")

                        authHeader = "Authorization: Digest username=\"$impi\", realm=\"$realm\", nonce=\"$nonce\", uri=\"sip:$DOMAIN\", response=\"$responseDigest\", auts=\"$autsBase64\", algorithm=AKAv1-MD5"
                        wasAutsSent = true
                    }
                }
            }

            if (authHeader.isEmpty()) {
                Log.w(TAG, "[USSI v5] Registration challenge failed or no credentials derived! Response: ${regResp1.lines().firstOrNull()}")
                return "IMS Registration failed: ${regResp1.lines().firstOrNull()}"
            }

            // ── STEP 3: SEND AUTHENTICATED REGISTER ──────────────────────────────────
            cseq++
            val authRegister = "REGISTER sip:$DOMAIN SIP/2.0\r\n" +
                    "Via: SIP/2.0/UDP [$localIpStr]:$localPort;branch=z9hG4bK-${UUID.randomUUID()}\r\n" +
                    "Max-Forwards: 70\r\n" +
                    "Route: <sip:[$pcscfIp]:$PCSCF_PORT;lr>\r\n" +
                    "From: <$impu>;tag=$regFromTag\r\n" +
                    "To: <$impu>\r\n" +
                    "Call-ID: $regCallId\r\n" +
                    "CSeq: $cseq REGISTER\r\n" +
                    "Contact: <sip:$msisdn@[$localIpStr]:$localPort>$contactParams;expires=$regExpires\r\n" +
                    "P-Visited-Network-ID: \"$DOMAIN\"\r\n" +
                    "P-Access-Network-Info: 3GPP-E-UTRAN-FDD; utran-cell-id-3gpp=510110000000000\r\n" +
                    "User-Agent: IM-client/OMA1.0 Sony-Xperia-10-III/1.0\r\n" +
                    authHeader + "\r\n" +
                    "Supported: path, sec-agree\r\n" +
                    "Expires: $regExpires\r\n" +
                    "Content-Length: 0\r\n\r\n"

            Log.i(TAG, "[USSI v5] Sending Authenticated REGISTER (cseq=$cseq) to [$pcscfIp]:$PCSCF_PORT...")
            socket.send(DatagramPacket(authRegister.toByteArray(Charsets.UTF_8), authRegister.length, pcscfAddr, PCSCF_PORT))

            val regPacket2 = DatagramPacket(buf, buf.size)
            socket.receive(regPacket2)
            var regResp2 = String(regPacket2.data, 0, regPacket2.length)
            Log.i(TAG, "[USSI v5] Received Authenticated REGISTER response #1:\n$regResp2")

            if (regResp2.contains("100 Trying")) {
                val nextPacket = DatagramPacket(buf, buf.size)
                socket.receive(nextPacket)
                regResp2 = String(nextPacket.data, 0, nextPacket.length)
                Log.i(TAG, "[USSI v5] Received Authenticated REGISTER response #2:\n$regResp2")
            }

            // If AUTS was sent, RFC 3310 Section 3.4 requires server to re-challenge with 401 and re-synchronized nonce
            if (wasAutsSent && regResp2.contains("401 Unauthorized")) {
                val syncAuthLine = regResp2.lines().firstOrNull { it.startsWith("WWW-Authenticate:", ignoreCase = true) }
                    ?: regResp2.substringAfter("WWW-Authenticate:", "").substringBefore("\r\n")
                val syncNonce = syncAuthLine.substringAfter("nonce=\"", "").substringBefore("\"")
                val syncOpaque = syncAuthLine.substringAfter("opaque=\"", "").substringBefore("\"")
                Log.i(TAG, "[USSI v5] Server accepted AUTS! Re-synchronized challenge nonce: $syncNonce")

                if (syncNonce.isNotEmpty()) {
                    val syncAkaResp = calculateSimAka(classLoader, syncNonce)
                    Log.i(TAG, "[USSI v5] Re-synchronized SIM AKA response: $syncAkaResp")
                    if (!syncAkaResp.isNullOrEmpty()) {
                        val syncAkaBytes = Base64.decode(syncAkaResp, Base64.DEFAULT)
                        if (syncAkaBytes.isNotEmpty() && syncAkaBytes[0] == 0xDB.toByte()) {
                            val sResLen = syncAkaBytes[1].toInt() and 0xFF
                            val sResBytes = syncAkaBytes.copyOfRange(2, 2 + sResLen)
                            val sResHex = bytesToHex(sResBytes)
                            Log.i(TAG, "[USSI v5] Re-synchronized Derived RES: $sResHex")

                            val mdRaw = MessageDigest.getInstance("MD5")
                            mdRaw.update("$impi:$realm:".toByteArray(Charsets.UTF_8))
                            mdRaw.update(sResBytes)
                            val sHa1 = mdRaw.digest().joinToString("") { "%02x".format(it) }

                            val sHa2 = md5Hex("REGISTER:sip:$DOMAIN")
                            val sRespDigest = md5Hex("$sHa1:$syncNonce:$sHa2")

                            var sAuthHdr = "Authorization: Digest username=\"$impi\", realm=\"$realm\", nonce=\"$syncNonce\", uri=\"sip:$DOMAIN\", response=\"$sRespDigest\", algorithm=AKAv1-MD5"
                            if (syncOpaque.isNotEmpty()) sAuthHdr += ", opaque=\"$syncOpaque\""

                            cseq++
                            val sAuthRegister = "REGISTER sip:$DOMAIN SIP/2.0\r\n" +
                                    "Via: SIP/2.0/UDP [$localIpStr]:$localPort;branch=z9hG4bK-${UUID.randomUUID()}\r\n" +
                                    "Max-Forwards: 70\r\n" +
                                    "Route: <sip:[$pcscfIp]:$PCSCF_PORT;lr>\r\n" +
                                    "From: <$impu>;tag=$regFromTag\r\n" +
                                    "To: <$impu>\r\n" +
                                    "Call-ID: $regCallId\r\n" +
                                    "CSeq: $cseq REGISTER\r\n" +
                                    "Contact: <sip:$msisdn@[$localIpStr]:$localPort>$contactParams;expires=$regExpires\r\n" +
                                    "P-Visited-Network-ID: \"$DOMAIN\"\r\n" +
                                    "P-Access-Network-Info: 3GPP-E-UTRAN-FDD; utran-cell-id-3gpp=510110000000000\r\n" +
                                    "User-Agent: IM-client/OMA1.0 Sony-Xperia-10-III/1.0\r\n" +
                                    sAuthHdr + "\r\n" +
                                    "Supported: path, sec-agree\r\n" +
                                    "Expires: $regExpires\r\n" +
                                    "Content-Length: 0\r\n\r\n"

                            Log.i(TAG, "[USSI v5] Sending Re-synchronized REGISTER (cseq=$cseq) to [$pcscfIp]:$PCSCF_PORT...")
                            socket.send(DatagramPacket(sAuthRegister.toByteArray(Charsets.UTF_8), sAuthRegister.length, pcscfAddr, PCSCF_PORT))

                            socket.receive(regPacket2)
                            regResp2 = String(regPacket2.data, 0, regPacket2.length)
                            if (regResp2.contains("100 Trying")) {
                                val nextPacket = DatagramPacket(buf, buf.size)
                                socket.receive(nextPacket)
                                regResp2 = String(nextPacket.data, 0, nextPacket.length)
                            }
                            Log.i(TAG, "[USSI v5] Received response after re-synchronization:\n$regResp2")
                        }
                    }
                }
            }

            // If not 200 OK and ha1HexStr is available, try fallback with hex string RES
            if (!regResp2.contains("200 OK") && ha1HexStr.isNotEmpty()) {
                val authLine2 = regResp2.lines().firstOrNull { it.startsWith("WWW-Authenticate:", ignoreCase = true) }
                    ?: regResp2.substringAfter("WWW-Authenticate:", "").substringBefore("\r\n")
                val nonce2 = authLine2.substringAfter("nonce=\"", "").substringBefore("\"").ifEmpty { nonce }
                val opaque2 = authLine2.substringAfter("opaque=\"", "").substringBefore("\"").ifEmpty { opaque }
                Log.i(TAG, "[USSI v5] Initial auth not 200 OK (${regResp2.lines().firstOrNull()}). Retrying with ha1HexStr ($ha1HexStr)...")
                val ha2 = md5Hex("REGISTER:sip:$DOMAIN")
                val respDigestHex = md5Hex("$ha1HexStr:$nonce2:$ha2")
                val authHdrHex = "Authorization: Digest username=\"$impi\", realm=\"$realm\", nonce=\"$nonce2\", uri=\"sip:$DOMAIN\", response=\"$respDigestHex\", algorithm=AKAv1-MD5" +
                        (if (opaque2.isNotEmpty()) ", opaque=\"$opaque2\"" else "")
                cseq++
                val authRegister2 = "REGISTER sip:$DOMAIN SIP/2.0\r\n" +
                        "Via: SIP/2.0/UDP [$localIpStr]:$localPort;branch=z9hG4bK-${UUID.randomUUID()}\r\n" +
                        "Max-Forwards: 70\r\n" +
                        "Route: <sip:[$pcscfIp]:$PCSCF_PORT;lr>\r\n" +
                        "From: <$impu>;tag=$regFromTag\r\n" +
                        "To: <$impu>\r\n" +
                        "Call-ID: $regCallId\r\n" +
                        "CSeq: $cseq REGISTER\r\n" +
                        "Contact: <sip:$msisdn@[$localIpStr]:$localPort>$contactParams;expires=$regExpires\r\n" +
                        "P-Visited-Network-ID: \"$DOMAIN\"\r\n" +
                        "P-Access-Network-Info: 3GPP-E-UTRAN-FDD; utran-cell-id-3gpp=510110000000000\r\n" +
                        "User-Agent: IM-client/OMA1.0 Sony-Xperia-10-III/1.0\r\n" +
                        authHdrHex + "\r\n" +
                        "Supported: path, sec-agree\r\n" +
                        "Expires: $regExpires\r\n" +
                        "Content-Length: 0\r\n\r\n"
                socket.send(DatagramPacket(authRegister2.toByteArray(Charsets.UTF_8), authRegister2.length, pcscfAddr, PCSCF_PORT))
                socket.receive(regPacket2)
                regResp2 = String(regPacket2.data, 0, regPacket2.length)
                if (regResp2.contains("100 Trying")) {
                    val nextPacket = DatagramPacket(buf, buf.size)
                    socket.receive(nextPacket)
                    regResp2 = String(nextPacket.data, 0, nextPacket.length)
                }
                Log.i(TAG, "[USSI v5] Received Authenticated REGISTER response (ha1HexStr retry):\n$regResp2")
            }

            if (!regResp2.contains("200 OK")) {
                Log.e(TAG, "[USSI v5] Authenticated registration was not accepted: ${regResp2.lines().firstOrNull()}")
                val warning = regResp2.lines().firstOrNull { it.startsWith("Warning:", ignoreCase = true) }
                return "Registration error: ${regResp2.lines().firstOrNull()}" + (if (!warning.isNullOrBlank()) "\n$warning" else "")
            }

            Log.i(TAG, "IMS Session REGISTERED successfully (200 OK)!")

            // ── STEP 4: SEND 3GPP TS 24.390 USSI INVITE ON REGISTERED SOCKET ────────
            val encodedDialstring = URLEncoder.encode(ussdCode, "UTF-8")
            val targetUri = "sip:$encodedDialstring;phone-context=$DOMAIN;user=dialstring@$DOMAIN"
            val inviteCallId = "ussi-${UUID.randomUUID()}@[$localIpStr]"
            val inviteBranch = "z9hG4bK-${UUID.randomUUID()}"
            val inviteFromTag = "ussi-${System.currentTimeMillis()}"
            val boundary = "----3GPP-USSI-Boundary-${UUID.randomUUID()}"

            val sdpPart = "v=0\r\n" +
                    "o=- ${System.currentTimeMillis()} 1 IN IP6 $localIpStr\r\n" +
                    "s=-\r\n" +
                    "c=IN IP6 $localIpStr\r\n" +
                    "t=0 0\r\n" +
                    "m=audio 0 RTP/AVP 96\r\n" +
                    "a=rtpmap:96 AMR-WB/16000\r\n"

            val xmlPart = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" +
                    "<ussd-data>\r\n" +
                    "    <language>en</language>\r\n" +
                    "    <ussd-string>$ussdCode</ussd-string>\r\n" +
                    "</ussd-data>\r\n"

            val body = "--$boundary\r\n" +
                    "Content-Type: application/sdp\r\n\r\n" +
                    sdpPart +
                    "--$boundary\r\n" +
                    "Content-Type: application/vnd.3gpp.ussd+xml\r\n\r\n" +
                    xmlPart +
                    "--$boundary--\r\n"

            val bodyBytes = body.toByteArray(Charsets.UTF_8)

            val inviteMsg = "INVITE $targetUri SIP/2.0\r\n" +
                    "Via: SIP/2.0/UDP [$localIpStr]:$localPort;branch=$inviteBranch\r\n" +
                    "Max-Forwards: 70\r\n" +
                    "Route: <sip:[$pcscfIp]:$PCSCF_PORT;lr>\r\n" +
                    "From: <$impu>;tag=$inviteFromTag\r\n" +
                    "To: <$targetUri>\r\n" +
                    "Call-ID: $inviteCallId\r\n" +
                    "CSeq: 1 INVITE\r\n" +
                    "Contact: <sip:$msisdn@[$localIpStr]:$localPort>\r\n" +
                    "P-Preferred-Identity: <$impu>\r\n" +
                    "Allow: INVITE, ACK, CANCEL, BYE, INFO, NOTIFY\r\n" +
                    "Recv-Info: g.3gpp.ussd\r\n" +
                    "Accept: application/vnd.3gpp.ussd+xml, application/sdp\r\n" +
                    "Content-Type: multipart/mixed; boundary=\"$boundary\"\r\n" +
                    "Content-Length: ${bodyBytes.size}\r\n\r\n" +
                    body

            Log.i(TAG, "Sending 3GPP TS 24.390 INVITE to [$pcscfIp]:$PCSCF_PORT:\n$inviteMsg")
            socket.send(DatagramPacket(inviteMsg.toByteArray(Charsets.UTF_8), inviteMsg.length, pcscfAddr, PCSCF_PORT))

            var attempts = 0
            while (attempts < 6) {
                attempts++
                val recvPacket = DatagramPacket(buf, buf.size)
                socket.receive(recvPacket)
                val response = String(recvPacket.data, 0, recvPacket.length)
                Log.i(TAG, "Received SIP response #$attempts:\n$response")

                val statusLine = response.lines().firstOrNull() ?: ""
                val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0

                // 100 Trying, 180 Ringing, 183 Session Progress -> wait for final
                if (statusCode in 100..199) {
                    continue
                }

                if (statusCode == 200) {
                    val toTag = response.lines().firstOrNull { it.startsWith("To:", ignoreCase = true) }
                        ?.substringAfter("tag=", "") ?: ""
                    val toHeader = if (toTag.isNotEmpty()) "<$targetUri>;tag=$toTag" else "<$targetUri>"
                    val ackMsg = "ACK $targetUri SIP/2.0\r\n" +
                            "Via: SIP/2.0/UDP [$localIpStr]:$localPort;branch=z9hG4bK-${UUID.randomUUID()}\r\n" +
                            "Max-Forwards: 70\r\n" +
                            "Route: <sip:[$pcscfIp]:$PCSCF_PORT;lr>\r\n" +
                            "From: <$impu>;tag=$inviteFromTag\r\n" +
                            "To: $toHeader\r\n" +
                            "Call-ID: $inviteCallId\r\n" +
                            "CSeq: 1 ACK\r\n" +
                            "Content-Length: 0\r\n\r\n"
                    socket.send(DatagramPacket(ackMsg.toByteArray(Charsets.UTF_8), ackMsg.length, pcscfAddr, PCSCF_PORT))
                    Log.i(TAG, "Sent SIP ACK for 200 OK")
                }

                // Extract USSD string from XML
                val startTag = "<ussd-string>"
                val endTag = "</ussd-string>"
                val start = response.indexOf(startTag)
                if (start != -1) {
                    val end = response.indexOf(endTag, start)
                    if (end != -1) {
                        return response.substring(start + startTag.length, end).trim()
                    }
                }

                // If 200 OK but body is empty, wait for follow-up INFO
                if (statusCode == 200) {
                    try {
                        socket.soTimeout = 3000
                        val infoPacket = DatagramPacket(buf, buf.size)
                        socket.receive(infoPacket)
                        val infoResp = String(infoPacket.data, 0, infoPacket.length)
                        Log.i(TAG, "Received follow-up SIP INFO:\n$infoResp")
                        val infoStart = infoResp.indexOf(startTag)
                        if (infoStart != -1) {
                            val infoEnd = infoResp.indexOf(endTag, infoStart)
                            if (infoEnd != -1) {
                                return infoResp.substring(infoStart + startTag.length, infoEnd).trim()
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Follow-up receive error: ${t.message}")
                    }
                }

                val warning = response.lines().firstOrNull { it.startsWith("Warning:", ignoreCase = true) }
                return if (!warning.isNullOrBlank()) {
                    "$statusLine\n$warning"
                } else {
                    statusLine
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "queryUssd error: ${t.message}", t)
            return "USSI error: ${t.message}"
        } finally {
            socket?.close()
        }
        return "No response from IMS core"
    }
}
