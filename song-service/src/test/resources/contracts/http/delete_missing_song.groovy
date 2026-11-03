import org.springframework.cloud.contract.spec.Contract

Contract.make {
    name 'delete_missing_song'
    request {
        method 'DELETE'
        urlPath('/songs') { queryParameters { parameter 'id': '99' } }
    }
    response {
        status 200
        headers { contentType(applicationJson()) }
        body('''{"ids": []}''')
    }
}
