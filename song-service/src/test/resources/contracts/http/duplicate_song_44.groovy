import org.springframework.cloud.contract.spec.Contract

Contract.make {
    name 'duplicate_song_44'
    request {
        method 'POST'
        urlPath('/songs')
        headers { contentType(applicationJson()) }
        body('''{"id": 44, "name": "Test Title", "artist": "Test Artist", "album": "Test Album", "duration": "00:07", "year": "2025"}''')
    }
    response {
        status 409
        headers { contentType(applicationJson()) }
        body('''{"errorMessage": "Metadata for resource ID=44 already exists", "errorCode": "409"}''')
    }
}
