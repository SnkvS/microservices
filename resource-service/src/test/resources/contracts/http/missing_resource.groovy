import org.springframework.cloud.contract.spec.Contract

Contract.make {
    name 'missing_resource'
    request {
        method 'GET'
        urlPath('/resources/99')
    }
    response {
        status 404
        headers { contentType(applicationJson()) }
        body('''{"errorMessage": "Resource with ID=99 not found", "errorCode": "404"}''')
    }
}
