package net.corda.tools.error.codes.server.application

import net.corda.tools.error.codes.server.application.annotations.Application
import net.corda.tools.error.codes.server.commons.events.PublishingEventSource
import net.corda.tools.error.codes.server.domain.ErrorCode
import net.corda.tools.error.codes.server.domain.ErrorDescriptionLocation
import net.corda.tools.error.codes.server.domain.InvocationContext
import net.corda.tools.error.codes.server.domain.PlatformEdition
import net.corda.tools.error.codes.server.domain.ReleaseVersion
import net.corda.tools.error.codes.server.domain.annotations.Adapter
import net.corda.tools.error.codes.server.domain.loggerFor
import reactor.core.publisher.Mono
import reactor.core.publisher.Mono.defer
import java.util.*
import javax.annotation.PreDestroy
import javax.inject.Inject
import javax.inject.Named

@Application
@Named
internal class CachingErrorDescriptionService @Inject constructor(@Adapter private val lookup: (ErrorCode, InvocationContext) -> Mono<Optional<out ErrorDescriptionLocation>>, private val retrieveCached: (ErrorCoordinates) -> Mono<Optional<out ErrorDescriptionLocation>>, private val addToCache: (ErrorCoordinates, ErrorDescriptionLocation) -> Mono<Unit>, @Named(CachingErrorDescriptionService.eventSourceQualifier) override val source: PublishingEventSource<ErrorDescriptionService.Event> = CachingErrorDescriptionService.EventSourceBean()) : ErrorDescriptionService {

    private companion object {

        private const val eventSourceQualifier = "CachingErrorDescriptionService_PublishingEventSource"
        private val logger = loggerFor<CachingErrorDescriptionService>()
    }

    // TODO sollecitom perhaps return a StackOverflow search query URL instead of not found on absent.
    override fun descriptionLocationFor(errorCode: ErrorCode, releaseVersion: ReleaseVersion, platformEdition: PlatformEdition, invocationContext: InvocationContext): Mono<Optional<out ErrorDescriptionLocation>> {

        // TODO sollecitom remove errorCode from ErrorDescriptionLocation.
        // TODO sollecitom add ErrorDescription with ErrorDescriptionLocation field, plus ErrorCode, ReleaseVersion and PlatformEdition.
        // TODO sollecitom return multiple ErrorDescription from `lookup`, as a Flux, and use the "closest" in terms of ReleaseVersion and PlatformEdition here.
        val coordinates = ErrorCoordinates(errorCode, releaseVersion, platformEdition)

        return coordinates.let(retrieveCached).orIfAbsent { lookup(coordinates.code, invocationContext).andIfPresent { addToCache(coordinates, it) } }.thenPublish(coordinates, invocationContext)
    }

    @PreDestroy
    override fun close() {

        source.close()
        logger.info("Closed")
    }

    private fun <ELEMENT : Any> Mono<Optional<out ELEMENT>>.andIfPresent(action: (ELEMENT) -> Mono<Unit>): Mono<Optional<out ELEMENT>> {

        return doOnNext { result -> result.ifPresent { action(it) } }
    }

    private fun <ELEMENT : Any> Mono<Optional<out ELEMENT>>.orIfAbsent(action: () -> Mono<Optional<out ELEMENT>>): Mono<Optional<out ELEMENT>> {

        return filter(Optional<*>::isPresent).switchIfEmpty(defer(action))
    }

    private fun Mono<Optional<out ErrorDescriptionLocation>>.thenPublish(coordinates: ErrorCoordinates, invocationContext: InvocationContext): Mono<Optional<out ErrorDescriptionLocation>> {

        return doOnSuccess { location -> completed(location?.orElse(null), coordinates.code, coordinates.releaseVersion, coordinates.platformEdition, invocationContext)?.let(source::publish) }
    }

    private fun completed(location: ErrorDescriptionLocation?, errorCode: ErrorCode, releaseVersion: ReleaseVersion, platformEdition: PlatformEdition, invocationContext: InvocationContext): ErrorDescriptionService.Event.Invocation.Completed.DescriptionLocationFor? {

        return if (location == null) ErrorDescriptionService.Event.Invocation.Completed.DescriptionLocationFor.WithoutDescriptionLocation(errorCode, releaseVersion, platformEdition, invocationContext) else null
    }

    @Named(eventSourceQualifier)
    private class EventSourceBean : PublishingEventSource<ErrorDescriptionService.Event>()
}

// This allows injecting functions instead of types.
@Application
@Named
internal class ErrorDescriptionLocator @Inject constructor(private val service: ErrorDescriptionService) : (ErrorCode, ReleaseVersion, PlatformEdition, InvocationContext) -> Mono<Optional<out ErrorDescriptionLocation>> {

    override fun invoke(errorCode: ErrorCode, releaseVersion: ReleaseVersion, platformEdition: PlatformEdition, invocationContext: InvocationContext) = service.descriptionLocationFor(errorCode, releaseVersion, platformEdition, invocationContext)
}