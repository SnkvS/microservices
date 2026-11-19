import org.springframework.cloud.contract.spec.Contract

Contract.make {
    name 'resource_processed'
    label 'resource_processed'
    input { triggeredBy('publishResource()') }
    outputMessage {
        sentTo('resources:resource.processed')
        body(resourceId: 42)
        headers { messagingContentType(applicationJson()) }
    }
}
