package trelloconsumer

import com.opencsv.CSVWriter
import grails.plugins.rest.client.RestBuilder

class Trello {

    def getJSON(path, params=null) {
        String baseUrl = "https://api.trello.com/1/"
        String options = ""
        String url = baseUrl + path + options
        if(params != null) {
            url = url + "&" + params
        }
        return new RestBuilder().get(url).json
    }

    def parseResponse() {
        def resp = getJSON("members/my/boards")

        //Board that we want to look at:
        // "Vizzuality Proposals Board"
        //Relevant lists:
        // * "Idea"
        // * "Writing a draft"
        // * "Edits/ re-write"
        // * "Draft sent"
        // * "Refine proposal"
        // * "Happy to Proceed"
        def dateNow = new Date()
        def sprints = []
        def myBoard
        for(board in resp){
            if(board['name'] == 'Vizzuality Proposals Board') {
                myBoard = board
            }
        }
        System.out.println("${myBoard['name']} - ${myBoard['id']}")

        System.out.println("Fetching timeline of events")
        def timeline = getJSON('/boards/'+myBoard['id']+'/actions',
                'filter=createCard,updateCard:idList,' +
                'updateCard:closed,' + 'moveCardFromBoard,moveCardToBoard,' + 'addMemberToCard,removeMemberFromCard')

        timeline = timeline.sort{ t1, t2 -> t1['data']['card']['id'] <=> t2['data']['card']['id'] }

        //Printing the timeline
        System.out.println("Timeline:")
        for(event in timeline){
            def card = event['data']['card']
            System.out.println("${card['name']} | ${event['date']} |  ${event['type']} ")
        }

        def cardData = []
        def listData = [:]

        cardData = getJSON('/boards/'+myBoard['id']+'/actions','fields=idList,labels&filter=all')
        for(item in getJSON('/boards/'+myBoard['id']+'/lists')) {
            listData.put(item['id'].toString(), item)
        }

        def currentListOf = [:]
        def currentLabelsOf = [:]
        for(item in cardData) {
            currentListOf.put(item['id'].toString(), listData[item['idList'].toString()])
            currentLabelsOf.put(item['id'].toString(), item['labels'])
        }

        /* ===========================================
        *  CALCULATE TIME SPENT BY A CARD IN EACH LIST
        *  =========================================== */
        def timeSpentInList = []
        // Used throughout the loop to determine who are the members of current card

        def members = []

        def previousCard
        def nextEvent
        def nextCard
        for(int i = 0; i < timeline.size(); i++) {
            def event = timeline[i]
            def card = event['data']['card']

            if (i == 0) {
                previousCard = null
            } else {
                previousCard = timeline[i - 1]['data']['card']
            }

            if (event['type'] == 'moveCardToBoard' && i < timeline.size() - 2) {
                /* A moveCardToBoard event happens when a card is moved into the board.
                 # The event following it is always the corresponding moveCardFromBoard
                 # of the other board which happens just a few milliseconds after
                 # moveCardToBard. This will result in a near-zero time-span for our
                 # moveCardToBoard. So let's look two events ahead for guidance. */
                nextEvent = timeline[i + 2]
                nextCard = nextEvent['data']['card']
            } else if (event['type'] != 'moveCardToBoard' && i < timeline.size() - 1) {
                nextEvent = timeline[i + 1]
                nextCard = nextEvent['data']['card']
            } else {
                nextEvent = nextCard = null
            }
            // Determine who are the members of the current card
            if (['addMemberToCard', 'removeMemberFromCard'].contains(event['type'])) {
                def membersIds = members.collect({ m -> m['id'] })
                if (event['type'] == 'addMemberToCard' && (previousCard == null || previousCard['id'] != card['id'])) {
                    //Start of timeline or we've moved to another card
                    members = [event['member']]
                } else if (event['type'] == 'addMemberToCard' && previousCard['id'] == card['id'] &&
                        !(membersIds.contains(event['member']['id']))) {
                    //Same card but new event
                    members.add(event['member'])
                } else if (event['type'] == 'removeMemberFromCard') {
                    //We don't care where we are in the timeline. Just remove
                    //that member from the members list.
                    def newMembers = []
                    for (int j = 0; j < members.size(); j++) {
                        if (members[j]['id'] != event['member']['id']) {
                            newMembers.add(members[j])
                        }
                    }
                    members = newMembers
                }
                //We don't need anything else from this
                //event. Move on to the next one
                continue
            }

            // This event is redundant. Its corresponding 'moveCardToBoard' will do.
            // So let's skip ahead to the next event.
            if (event['type'] == 'moveCardFromBoard') {
                continue
            }
            // TODO: There are instances when the first item is a moveCardFromBoard.
            // Handle that here!
            Date datetimeIn = Date.parse("yyyy-MM-dd", event['date'])

            // Based on what's next, determine when the card left the list
            // as well as compute the time the card spent on that list
            def datetimeOut
            def duration
            if (nextEvent && nextCard['id'] == card['id']) {
                datetimeOut = Date.parse("yyyy-MM-dd", nextEvent['date'])
                duration = datetimeOut - datetimeIn
            } else {
                datetimeOut = "N/A"
                use(groovy.time.TimeCategory) {
                    duration = dateNow - datetimeIn
                    duration = duration.seconds
                }
            }
            // Determine in which list this event happened
            def cardList
            if(event['data'].keySet().contains('listAfter')) {
                cardList = event['data']['listAfter']
            } else if(event['data'].keySet().contains('list')){
                cardList = event['data']['list']
            } else {
                // The event doesn't have the card's list information. Let's
                // assume it's the current list where the card is in.
                cardList = currentListOf[card['id']]
            }
            // If it's currently in the 'Done' list and it never left,
            // then it was moved to done correctly. Don't count its time.
            def cardName
            if(['Happy to Proceed'].contains(cardList['name']) && datetimeOut == 'N/A') {
                duration = ''
                cardName = card['name']
                if(cardName.size() > 30){
                    cardName = cardName.substring(0,30) + '...'
                }
            }

            def labels
            if(currentLabelsOf.keySet().contains(card['id'])) {
                labels = currentLabelsOf[card['id']]
            } else {
                labels = []
            }

            // Finally, record the data
            timeSpentInList.add([
                card_id: card['id'],
                card_name: cardName,
                board_name: event['data']['board']['name'],
                list_name: cardList['name'],
                datetime_in: datetimeIn,
                datetime_out: datetimeOut,
                duration: duration,
                members: members,
                labels: labels
            ])
        }
        CSVWriter writer = new CSVWriter(new FileWriter("yourfile.csv"));
        // feed in your array (or convert your data to an array)
        String[] header =  ["Card ID", "Card Name", "Members", "Board", "List",
                            "In", "Out", "Duration (Seconds)"]
        writer.writeNext(header);
        String[] row
        for(item in timeSpentInList){
            row = [
                item.get("card_id").toString(),
                item.get("card_name").toString(),
                item.get("members").toString(),
                item.get("board_name").toString(),
                item.get("list_name").toString(),
                item.get("datetime_in").toString(),
                item.get("datetime_out").toString(),
                item.get("duration").toString()
            ]
            writer.writeNext(row)
        }
        writer.close();
        return "ZERP"
    }
}
