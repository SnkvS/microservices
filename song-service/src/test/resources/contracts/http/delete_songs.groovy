import org.springframework.cloud.contract.spec.Contract

Contract.make {
    name 'delete_songs'
    request {
        method 'DELETE'
        urlPath('/songs') { queryParameters { parameter 'id': '43,99' } }
    }
    response {
        status 200
        headers { contentType(applicationJson()) }
        body('''{"ids": [43]}''')
    }
}
