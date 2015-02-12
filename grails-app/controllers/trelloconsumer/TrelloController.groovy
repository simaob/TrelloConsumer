package trelloconsumer
import grails.plugins.rest.client.RestBuilder

class TrelloController {

    def index() {
        String url = "https://api.trello.com/1/boards/R5N56ooq/cards?key=330d80b191e94503a66cb52b2f75766e&token=2f0786548622f9142c9547855878ab386c0d66c37d9ca6c77002aaace55014d0"

        def resp = new RestBuilder().get(url)
        render resp.json
    }
}
