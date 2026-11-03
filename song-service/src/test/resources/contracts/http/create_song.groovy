import org.springframework.cloud.contract.spec.Contract

Contract.make {
    name 'create_song'
    request {
        method 'POST'
        urlPath('/songs')
        headers { contentType(applicationJson()) }
        body('''{"id": 42, "name": "Test Title", "artist": "Test Artist", "album": "Test Album", "duration": "00:07", "year": "2025"}''')
    }
    response {
        status 200
        headers { contentType(applicationJson()) }
        body('''{"id": 42}''')
    }
}
