package com.github.badoualy.telegram.api

import com.github.badoualy.telegram.api.utils.InputFileLocation
import com.github.badoualy.telegram.mtproto.ApiCallback
import com.github.badoualy.telegram.mtproto.MTProtoHandler
import com.github.badoualy.telegram.mtproto.auth.AuthKey
import com.github.badoualy.telegram.mtproto.auth.AuthKeyCreation
import com.github.badoualy.telegram.mtproto.auth.AuthResult
import com.github.badoualy.telegram.mtproto.exception.SecurityException
import com.github.badoualy.telegram.mtproto.model.DataCenter
import com.github.badoualy.telegram.mtproto.time.MTProtoTimer
import com.github.badoualy.telegram.tl.api.*
import com.github.badoualy.telegram.tl.api.request.*
import com.github.badoualy.telegram.tl.api.upload.TLFile
import com.github.badoualy.telegram.tl.core.TLMethod
import com.github.badoualy.telegram.tl.core.TLObject
import com.github.badoualy.telegram.tl.exception.RpcErrorException
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.ClosedChannelException
import java.util.*
import java.util.concurrent.TimeoutException
import java.util.logging.Logger

internal class DefaultTelegramClient internal constructor(val application: TelegramApp, val apiStorage: TelegramApiStorage,
                                                          val preferredDataCenter: DataCenter,
                                                          val updateCallback: UpdateCallback?,
                                                          val tag: String) : TelegramApiWrapper(), TelegramClient, ApiCallback {

    private var mtProtoHandler: MTProtoHandler? = null
    private var authKey: AuthKey? = null
    private var dataCenter: DataCenter? = null
    private var closed = false

    private val authKeyMap = HashMap<Int, AuthKey>()
    private val exportedHandlerMap = HashMap<Int, MTProtoHandler>()
    private val exportedHandlerTimeoutMap = HashMap<Int, Long>()

    private var timeoutDuration: Long = 5000L
    private var exportedHandlerTimeout: Long = 15000L

    private var generateAuthKey: Boolean

    init {
        authKey = apiStorage.loadAuthKey()
        dataCenter = apiStorage.loadDc()
        generateAuthKey = authKey == null

        if (dataCenter == null) {
            if (!generateAuthKey) {
                apiStorage.deleteAuthKey()
                apiStorage.saveSession(null)
                throw RuntimeException("Found an authorization key in storage, but the DC configuration was not found, deleting authorization key")
            }
            logger.warning("No data center found in storage, using preferred ${preferredDataCenter.toString()}")
            dataCenter = preferredDataCenter
        }

        // No need to check DC if we have an authKey in storage
        init(checkNearestDc = generateAuthKey)
        logger.info("Client ready")
    }

    private fun init(checkNearestDc: Boolean = true) {
        logger.fine("init() $checkNearestDc")
        mtProtoHandler =
                if (generateAuthKey) MTProtoHandler(generateAuthKey(), this, tag)
                else MTProtoHandler(dataCenter!!, authKey!!, apiStorage.loadSession(), this, tag)
        mtProtoHandler!!.startWatchdog()

        try {
            // Call to initConnection to setup information about this app for the user to see in "active sessions"
            // Also will indicate to Telegram which layer to use through InvokeWithLayer
            // Re-call every time to ensure connection is alive and to update layer
            if (checkNearestDc)
                ensureNearestDc(initConnection(mtProtoHandler!!, TLRequestHelpGetNearestDc()))
            else {
                if (!generateAuthKey) {
                    try {
                        // GetNearestDc will not start updates: // TODO: replace with getDifference for updates
                        initConnection(mtProtoHandler!!, TLRequestUpdatesGetState())
                        return
                    } catch(e: RpcErrorException) {
                        // User may not be signed in already, in this case, ignore exception
                        if (e.code != 401)
                            throw e
                    }
                }
                initConnection(mtProtoHandler!!, TLRequestHelpGetNearestDc())
            }
        } catch(e: Exception) {
            mtProtoHandler?.close()
            if (e is RpcErrorException && e.code == -404)
                throw SecurityException("Your authorization key is invalid (error ${e.code})")
            throw e
        }
    }

    private fun generateAuthKey(): AuthResult {
        val authResult = AuthKeyCreation.createAuthKey(dataCenter!!, tag) ?: throw RuntimeException("Couldn't generate authorization key")
        authKey = authResult.authKey
        apiStorage.saveAuthKey(authKey!!)
        apiStorage.saveDc(dataCenter!!)
        return authResult
    }

    @Throws(RpcErrorException::class, IOException::class)
    private fun <T : TLObject> initConnection(mtProtoHandler: MTProtoHandler, method: TLMethod<T>): T {
        logger.fine("Init connection with method ${method.toString()}")
        val initConnectionRequest = TLRequestInitConnection(application.apiId, application.deviceModel, application.systemVersion, application.appVersion, application.langCode, method)
        val result = executeRpcQuery(TLRequestInvokeWithLayer(Kotlogram.API_LAYER, initConnectionRequest), mtProtoHandler)
        return result
    }

    @Throws(RpcErrorException::class, IOException::class)
    private fun ensureNearestDc(nearestDc: TLNearestDc) {
        logger.fine("ensureNearestDc()")
        if (nearestDc.thisDc != nearestDc.nearestDc) {
            logger.warning("Current DC${nearestDc.thisDc} is not the nearest (DC${nearestDc.nearestDc})")
            if (!generateAuthKey) {
                // Key was provided, yet selected DC is not the nearest
                // TODO: Should handle authKey migration via auth.exportAuthorization
                mtProtoHandler?.close()
                throw RuntimeException("You tried to connect to an incorrect data center (DC${nearestDc.thisDc}) with an authorization key in storage, please connect to the nearest (DC${nearestDc.nearestDc})")
            }
            migrate(nearestDc.nearestDc)
        } else {
            logger.info("Connected to the nearest DC${nearestDc.thisDc}")
        }
    }

    override fun setTimeout(timeout: Long) {
        this.timeoutDuration = timeout
    }

    override fun setExportedClientTimeout(timeout: Long) {
        this.exportedHandlerTimeout = timeout
    }

    override fun close() = close(true)

    override fun close(cleanUp: Boolean) {
        closed = true
        try {
            mtProtoHandler?.close()
        }catch (e: Exception){
        }
        if (cleanUp)
            Kotlogram.cleanUp()
        apiStorage.saveSession(mtProtoHandler!!.session)
    }

    override fun isClosed() = closed

    override fun getDownloaderClient() = DefaultTelegramClient(application, ReadOnlyApiStorage(authKey!!, mtProtoHandler!!.session), preferredDataCenter, updateCallback, "Downloader:$tag")

    /** Queue method now, without handling the result or any possible error */
    override fun <T : TLObject> queueMethodImmediate(method: TLMethod<T>, validityTimeout: Long) {
        queueMethod(method, MTProtoHandler.QUEUE_TYPE_DISCARD, validityTimeout, Long.MAX_VALUE)?.subscribe()
    }

    override fun <T : TLObject> queueMethod(method: TLMethod<T>, type: Int, validityTimeout: Long, timeout: Long) = mtProtoHandler?.queueMethod(method, type, validityTimeout, timeout)

    @Throws(RpcErrorException::class, IOException::class)
    override fun <T : TLObject> executeRpcQuery(method: TLMethod<T>) = super.executeRpcQuery(method)

    @Throws(RpcErrorException::class, IOException::class)
    override fun <T : TLObject> executeRpcQueries(methods: List<TLMethod<T>>) = executeRpcQueries(methods, mtProtoHandler!!)

    @Throws(RpcErrorException::class, IOException::class)
    override fun <T : TLObject> executeRpcQueries(methods: List<TLMethod<T>>, dcId: Int): List<T> {
        logger.fine("executeRpcQuery ${methods.joinToString(", ")} on DC$dcId")
        if (Kotlogram.getDcById(dcId) == dataCenter)
            return executeRpcQueries(methods)

        logger.info("Need to export handler")
        val exportedHandler = getExportedMTProtoHandler(dcId)
        try {
            return executeRpcQueries(methods, exportedHandler)
        } finally {
            releaseExportedHandler(exportedHandler, dcId)
        }
    }

    @Throws(RpcErrorException::class, IOException::class)
    private fun <T : TLObject> executeRpcQuery(method: TLMethod<T>, mtProtoHandler: MTProtoHandler, attemptCount: Int = 0) = executeRpcQueries(listOf(method), mtProtoHandler, attemptCount).first()

    @Throws(RpcErrorException::class, IOException::class)
    private fun <T : TLObject> executeRpcQueries(methods: List<TLMethod<T>>, mtProtoHandler: MTProtoHandler, attemptCount: Int = 0): List<T> {
        // BlockingObservable.first() will throw a RuntimeException if onError() is called by observable
        try {
            return mtProtoHandler.executeMethodsSync(methods, timeoutDuration)
        } catch(exception: RuntimeException) {
            when (exception.cause) {
                is RpcErrorException -> {
                    val rpcException = (exception.cause as RpcErrorException)
                    if (rpcException.code == 303) {
                        // DC error
                        logger.severe("Received DC error: ${rpcException.toString()}")
                        if (rpcException.tag.startsWith("PHONE_MIGRATE_") || rpcException.tag.startsWith("NETWORK_MIGRATE_")) {
                            val dcId = rpcException.tagInteger
                            logger.info("Repeat request after migration on DC$dcId")
                            migrate(dcId)
                            return executeRpcQueries(methods)
                        } else if (rpcException.tag.startsWith("FILE_MIGRATE_")) {
                            val dcId = rpcException.tagInteger
                            logger.info("Repeat request with new handler on DC$dcId")
                            val exportedHandler = getExportedMTProtoHandler(dcId)
                            try {
                                return executeRpcQueries(methods, exportedHandler)
                            } finally {
                                releaseExportedHandler(exportedHandler, dcId)
                            }
                        }
                    }
                    logger.severe("Unhandled RpcError ${rpcException.toString()}")
                    throw RpcErrorException(rpcException.code, rpcException.tag) // Better stack trace
                }
                is TimeoutException, is ClosedChannelException, is IOException -> {
                    if (attemptCount < 2) {
                        // Experimental, try to resend request ...
                        logger.severe("Attempting MtProtoHandler reset after failure")
                        mtProtoHandler.resetConnection()
                        val result = executeRpcQueries(methods, mtProtoHandler, attemptCount + 1)
                        logger.fine("Reset worked")
                        return result
                    }
                    throw TimeoutException("Request timed out")
                }
                //is IOException -> throw exception.cause as IOException
                else -> throw exception
            }
        }
    }

    override fun downloadSync(inputLocation: InputFileLocation, size: Int, partSize: Int, outputStream: OutputStream) {
        var offset = 0
        val methods = ArrayList<TLMethod<TLFile>>()
        do {
            methods.clear()
            for (i in 0..5) {
                val limit = Math.min(partSize, size - offset)
                methods.add(TLRequestUploadGetFile(inputLocation.inputFileLocation, offset, limit))
                offset += limit
                if (offset >= size)
                    break
            }

            executeRpcQueries(methods, inputLocation.dcId).forEach { part -> outputStream.write(part.bytes.data) }
            outputStream.flush()
        } while (offset < size)

        outputStream.flush()
        outputStream.close()
    }

    @Throws(RpcErrorException::class, IOException::class)
    override fun authSendCode(allowFlashcall: Boolean, phoneNumber: String, currentNumber: Boolean) = super.authSendCode(allowFlashcall, phoneNumber, currentNumber, application.apiId, application.apiHash)!!

    @Throws(RpcErrorException::class, IOException::class)
    override fun <T : TLObject> initConnection(query: TLMethod<T>) = executeRpcQuery(TLRequestInitConnection(application.apiId, application.deviceModel, application.systemVersion, application.appVersion, application.langCode, query))!!

    @Throws(RpcErrorException::class, IOException::class)
    override fun messagesSendMessage(peer: TLAbsInputPeer, message: String, randomId: Long) = super.messagesSendMessage(true, false, false, false, peer, null, message, randomId, null, null)!!

    private fun migrate(dcId: Int) {
        logger.info("Migrating to DC$dcId")
        mtProtoHandler?.close()
        authKey = null
        dataCenter = Kotlogram.getDcById(dcId)
        apiStorage.deleteAuthKey()
        apiStorage.deleteDc()
        apiStorage.saveSession(null)
        generateAuthKey = true

        init(checkNearestDc = false)
    }

    @Throws(RpcErrorException::class, IOException::class)
    private fun getExportedMTProtoHandler(dcId: Int): MTProtoHandler {
        logger.info("getExportedMTProtoHandler(DC$dcId)")

        var cachedHandler: MTProtoHandler? = null
        synchronized(exportedHandlerMap) {
            cachedHandler = exportedHandlerMap.remove(dcId)
        }

        if (cachedHandler != null)
            logger.fine("Using cached handler")

        return cachedHandler ?: if (authKeyMap.contains(dcId)) {
            logger.fine("Already have key for DC$dcId")
            val authKey = authKeyMap[dcId]!!
            val mtProtoHandler = MTProtoHandler(Kotlogram.getDcById(dcId), authKey, null, null, tag)
            mtProtoHandler.startWatchdog()
            initConnection(mtProtoHandler, TLRequestHelpGetNearestDc())
            mtProtoHandler
        } else {
            logger.fine("Creating new handler on DC$dcId")
            val dc = Kotlogram.getDcById(dcId)
            val exportedAuthorization = authExportAuthorization(dcId)
            val authResult = AuthKeyCreation.createAuthKey(dc, tag) ?: throw IOException("Couldn't create authorization key on DC$dcId")
            val mtProtoHandler = MTProtoHandler(authResult, null, tag)
            mtProtoHandler.startWatchdog()
            initConnection(mtProtoHandler, TLRequestAuthImportAuthorization(exportedAuthorization.id, exportedAuthorization.bytes))
            authKeyMap.put(dcId, authResult.authKey)
            mtProtoHandler
        }
    }

    private fun releaseExportedHandler(mtProtoHandler: MTProtoHandler, dcId: Int) {
        synchronized(exportedHandlerMap) {
            if (exportedHandlerMap.containsKey(dcId)) {
                mtProtoHandler.close()
            } else {
                exportedHandlerMap.put(dcId, mtProtoHandler)
            }
            MTProtoTimer.schedule(exportedHandlerTimeout, { onExportedHandlerTimeout(dcId) })
            exportedHandlerTimeoutMap.put(dcId, System.currentTimeMillis() + exportedHandlerTimeout)
        }
    }

    private fun onExportedHandlerTimeout(dcId: Int) {
        synchronized(exportedHandlerMap) {
            if (System.currentTimeMillis() >= exportedHandlerTimeoutMap[dcId] ?: -1) {
                exportedHandlerMap.remove(dcId)?.close()
                exportedHandlerTimeoutMap.remove(dcId)
            }
        }
    }

    override fun onUpdates(update: TLAbsUpdates) {
        when (update) {
            is TLUpdates -> updateCallback?.onUpdates(this, update) // Multiple messages
            is TLUpdatesCombined -> updateCallback?.onUpdatesCombined(this, update)
            is TLUpdateShort -> updateCallback?.onUpdateShort(this, update)
            is TLUpdateShortChatMessage -> updateCallback?.onShortChatMessage(this, update) // group new message
            is TLUpdateShortMessage -> updateCallback?.onShortMessage(this, update) // 1v1 new message
            is TLUpdateShortSentMessage -> updateCallback?.onShortSentMessage(this, update)
            is TLUpdatesTooLong -> updateCallback?.onUpdateTooLong(this) // Warn that the client should refresh manually
        }
    }

    companion object {
        private val logger = Logger.getLogger("MTproto")
    }
}