package tech.sharply.spring_disruptor_mediatr.mediator

import com.lmax.disruptor.EventFactory
import com.lmax.disruptor.EventHandler
import com.lmax.disruptor.EventTranslatorOneArg
import com.lmax.disruptor.dsl.Disruptor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEvent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ThreadFactory
import javax.annotation.PostConstruct

interface Mediator {

    /**
     * Request handling
     */
    fun <TRequest : Request<TResponse>, TResponse> dispatchBlocking(request: TRequest): TResponse

    fun <TRequest : Request<TResponse>, TResponse> dispatchAsync(request: TRequest): CompletableFuture<TResponse>

    fun <TEvent : ApplicationEvent> publishEvent(event: TEvent)

}

/**
 * Mediator implementation that uses the one disruptor for commands and requests and another one for events.
 */
class DisruptorMediatorImpl(
    context: ApplicationContext,
) : Mediator {

    companion object {
        private val log: Logger = LoggerFactory.getLogger(DisruptorMediatorImpl::class.java)
    }

    private val registry: Registry = RegistryImpl(context)

    private val disruptor = Disruptor(
        EventFactory { CompletableRequestWrapper.empty() },
        1024,
        ThreadFactory { r -> Thread(r) }
    )

    private val eventsDisruptor = Disruptor(
        EventFactory { EventWrapper.empty() },
        1024,
        ThreadFactory { r -> Thread(r) }
    )

    @PostConstruct
    private fun init() {
        disruptor.handleEventsWith(EventHandler { wrapper, _, _ ->
            if (wrapper.payload == null) {
                return@EventHandler
            }

            val request = wrapper.payload!!

            val handler = getRequestHandler(request)
            if (handler == null) {
                log.info("No handler found for request type: " + request.javaClass)
                return@EventHandler
            }
            try {
                val result = handler.handle(request)
                wrapper.completableFuture.complete(result)
            } catch (ex: Exception) {
                wrapper.completableFuture.completeExceptionally(ex)
            }

            log.info("Consumer for request: " + wrapper.payload!! + " consumed on " + Thread.currentThread().id)
        })

        disruptor.start()

        eventsDisruptor.handleEventsWith(EventHandler { wrapper, _, _ ->
            if (wrapper.payload == null) {
                return@EventHandler
            }

            val handlers = registry.getEventHandlers(wrapper.payload!!.javaClass)
            if (handlers.isEmpty()) {
                log.info("No handler found for request type: " + wrapper.payload!!.javaClass)
                return@EventHandler
            }

            for (handler in handlers) {
                handler.handle(wrapper.payload!!)
            }
            log.info("Handled event " + wrapper.payload!! + " on " + Thread.currentThread().id)
        })
        eventsDisruptor.start()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <TRequest : Request<TResponse>, TResponse> getRequestHandler(request: TRequest): RequestHandler<TRequest, TResponse>? {
        return when (request) {
            is Command<*> -> {
                registry.getCommandHandler((request as Command<*>).javaClass) as RequestHandler<TRequest, TResponse>
            }
            is Query<*> -> {
                registry.getQueryHandler((request as Query<*>).javaClass) as RequestHandler<TRequest, TResponse>
            }
            else -> {
                null
            }
        }
    }

    override fun <TRequest : Request<TResponse>, TResponse> dispatchBlocking(request: TRequest): TResponse {
        // find the handle and execute it on the current thread
        val handler = getRequestHandler(request)
            ?: throw IllegalArgumentException("No handler found for request type " + request.javaClass)
        log.info("Executing request $request blocking on thread: ${Thread.currentThread().id}")
        return handler.handle(request)
    }

    override fun <TRequest : Request<TResponse>, TResponse> dispatchAsync(request: TRequest): CompletableFuture<TResponse> {
        val future = CompletableFuture<TResponse>()
        disruptor.publishEvent(getTranslator(future), request)
        return future
    }

    override fun <TEvent : ApplicationEvent> publishEvent(event: TEvent) {
        eventsDisruptor.publishEvent(getTranslator(), event)
    }

//    override fun <TEvent : ApplicationEvent> handleEvents(consumer: Consumer<TEvent>) {
//        val handler = EventHandler<EventWrapper<TEvent>> { wrapper, _, _ ->
//            if (wrapper.payload == null) {
//                return@EventHandler
//            }
//
//            consumer.accept(wrapper.payload!!)
//            log.info("Consumer for request: " + wrapper.payload!! + " consumed on " + Thread.currentThread().id)
//        } as EventHandler<EventWrapper<ApplicationEvent>>
//
//        eventsDisruptor.handleEventsWith(handler)
//    }

    @Suppress("UNCHECKED_CAST")
    private fun <TRequest : Request<TResponse>, TResponse> getTranslator(
        completableFuture: CompletableFuture<TResponse>?
    ): EventTranslatorOneArg<CompletableRequestWrapper<Request<Any?>, Any?>, TRequest> {
        return EventTranslatorOneArg<CompletableRequestWrapper<Request<Any?>, Any?>, TRequest> { wrapper, _, input ->
            if (wrapper == null) {
                return@EventTranslatorOneArg
            }

            wrapper.payload = input as Request<Any?>
            wrapper.completableFuture = completableFuture as CompletableFuture<Any?>? ?: CompletableFuture<Any?>()
        }
    }

    private fun <TEvent : ApplicationEvent> getTranslator(): EventTranslatorOneArg<EventWrapper<ApplicationEvent>, TEvent> {
        return EventTranslatorOneArg<EventWrapper<ApplicationEvent>, TEvent> { wrapper, _, input ->
            if (wrapper == null) {
                return@EventTranslatorOneArg
            }
            wrapper.payload = input as ApplicationEvent
        }
    }

    private class CompletableRequestWrapper<TRequest : Request<TResponse>, TResponse>(
        var payload: TRequest?,
        var completableFuture: CompletableFuture<TResponse> = CompletableFuture()
    ) {

        fun clear() {
            this.payload = null
            this.completableFuture = CompletableFuture<TResponse>()
        }

        companion object {
            fun empty(): CompletableRequestWrapper<Request<Any?>, Any?> {
                return CompletableRequestWrapper(null)
            }
        }
    }

    private class EventWrapper<T : ApplicationEvent>(
        var payload: T?
    ) {

        companion object {
            fun empty(): EventWrapper<ApplicationEvent> {
                return EventWrapper(null)
            }
        }

        override fun toString(): String {
            return "EventWrapper(payload=$payload)"
        }

    }

}

