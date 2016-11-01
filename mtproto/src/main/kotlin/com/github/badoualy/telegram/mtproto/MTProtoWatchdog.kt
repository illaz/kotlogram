package com.github.badoualy.telegram.mtproto

import com.github.badoualy.telegram.mtproto.transport.MTProtoConnection
import com.github.badoualy.telegram.mtproto.util.NamedThreadFactory
import rx.Observable
import rx.Subscriber
import java.io.IOException
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.*
import java.util.concurrent.Executors
import java.util.logging.Logger

/**
 * Permanently listen for messages on given MTProtoConnection and wrap everything in an Observable, each message will be send
 * to the subscriber
 */
internal object MTProtoWatchdog : Runnable {

    private val logger = Logger.getLogger("MTproto")

    private val SELECT_TIMEOUT_DELAY = 10 * 1000L // 10 seconds

    private val selector = Selector.open()
    private val keyMap = HashMap<SelectionKey, MTProtoConnection>()

    private val connectionList = ArrayList<MTProtoConnection>()
    private val subscriberMap = HashMap<MTProtoConnection, Subscriber<in ByteArray>>()

    private val executor = Executors.newSingleThreadExecutor(NamedThreadFactory(javaClass.simpleName, true))
    private val pool = Executors.newCachedThreadPool(NamedThreadFactory("${javaClass.simpleName}-exec")) // TODO fixed pool?

    private var dirty = false
    private var running = false

    override fun run() {
        while (true) {
            if (dirty) {
                synchronized(this) {
                    connectionList
                            .filterNot { keyMap.containsValue(it) }
                            .forEach { keyMap.put(it.register(selector), it) }
                    dirty = false
                }
            }

            if (selector.select(SELECT_TIMEOUT_DELAY) > 0) {
                synchronized(this) {
                    selector.selectedKeys().forEach { key ->
                        key.interestOps(0)
                        val connection = keyMap[key]
                        if (connection != null) {
                            pool.execute {
                                if (!connection.isOpen())
                                    return@execute
                                val wentGood = readMessage(connection)

                                // Done reading
                                if (wentGood && key.isValid) {
                                    key.interestOps(SelectionKey.OP_READ)
                                    selector.wakeup()
                                } else if (!wentGood) {
                                    stop(connection)
                                }
                            }
                        }
                    }
                }
                selector.selectedKeys().clear()
            }

            // Avoid synchronizing each loop
            if (connectionList.isEmpty()) {
                synchronized(this) {
                    if (connectionList.isEmpty()) {
                        running = false
                        logger.warning("Stopping watchdog...")
                        return
                    }
                }
            }
        }
    }

    private fun readMessage(connection: MTProtoConnection): Boolean {
        logger.info("readMessage()")
        val subscriber = subscriberMap[connection]
        if (subscriber == null || subscriber.isUnsubscribed || !connectionList.contains(connection)) {
            logger.warning("Subscribed already unsubscribed, dropping")
            return false
        }

        try {
            val message = connection.readMessage()
            logger.fine("New message of length: ${message.size}")
            subscriber.onNext(message)
        } catch (e: IOException) {
            // Silent fail if no subscriber
            if (!subscriber.isUnsubscribed) {
                logger.severe("Sending exception to subscriber")
                subscriber.onError(e)
            }

            logger.warning("Already unsubscribed")
            return false
        }

        return true
    }

    fun start(connection: MTProtoConnection): Observable<ByteArray> = Observable.create<ByteArray> { s ->
        logger.info("Adding ${connection.tag} to watchdog")
        synchronized(this) {
            connectionList.add(connection)
            subscriberMap.put(connection, s)

            dirty = true
            if (!running) {
                running = true
                executor.execute(this)
            }
        }
        selector.wakeup()
    }

    fun stop(connection: MTProtoConnection) {
        logger.info("Stopping ${connection.tag}")
        synchronized(this) {
            connectionList.remove(connection)
            val subscriber = subscriberMap.remove(connection)
            subscriber?.unsubscribe()
            val key = connection.unregister()
            if (key != null) keyMap.remove(key)
        }
    }

    fun cleanUp() {
        logger.warning("==================== SHUTTING DOWN WATCHDOG ====================")
        executor.shutdownNow()
        pool.shutdownNow()
    }
}