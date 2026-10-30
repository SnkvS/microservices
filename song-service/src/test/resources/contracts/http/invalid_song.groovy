import org.springframework.cloud.contract.spec.Contract

Contract.make {
    name 'invalid_song'
    request {
        method 'POST'
        urlPath('/songs')
        headers { contentType(applicationJson()) }
        body('''{"id": 45, "name": "Test Title", "artist": "Test Artist", "album": "Test Album", "duration": "01:60", "year": "2025"}''')
    }
    response {
        status 400
        headers { contentType(applicationJson()) }
        body('''{"errorMessage": "Validation error", "errorCode": "400", "details": {"duration": "Duration must be in mm:ss format with leading zeros"}}''')
    }
}
