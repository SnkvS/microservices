import org.springframework.cloud.contract.spec.Contract

Contract.make {
    name 'missing_song'
    request {
        method 'GET'
        urlPath('/songs/99')
    }
    response {
        status 404
        headers { contentType(applicationJson()) }
        body('''{"errorMessage": "Song metadata for ID=99 not found", "errorCode": "404"}''')
    }
}
