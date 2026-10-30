import org.springframework.cloud.contract.spec.Contract

Contract.make {
    name 'download_resource'
    request { method 'GET'; url '/resources/42' }
    response {
        status 200
        headers { contentType('audio/mpeg') }
        body(fileAsBytes('valid.mp3'))
    }
}
