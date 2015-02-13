package trelloconsumer
import grails.plugins.rest.client.RestBuilder

class TrelloController {

    def index() {

        def trello = new Trello()

        render trello.parseResponse()
    }
}
