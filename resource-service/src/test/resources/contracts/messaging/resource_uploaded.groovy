import org.springframework.cloud.contract.spec.Contract

Contract.make {
    name 'resource_uploaded'
    label 'resource_uploaded'
    input { triggeredBy('publishResource()') }
    outputMessage {
        sentTo('resources:resource.uploaded')
        body(resourceId: 42)
        headers { messagingContentType(applicationJson()) }
    }
}
