import org.springframework.cloud.contract.spec.Contract

Contract.make {
    name 'get_song_44'
    request {
        method 'GET'
        urlPath('/songs/44')
    }
    response {
        status 200
        headers { contentType(applicationJson()) }
        body('''{"id": 44, "name": "Other Title", "artist": "Test Artist", "album": "Test Album", "duration": "00:07", "year": "2025"}''')
    }
}
