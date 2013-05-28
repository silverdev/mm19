package mm19.game;

import java.util.ArrayList;
import java.util.Timer;

import mm19.exceptions.EngineException;
import mm19.exceptions.InputException;
import mm19.exceptions.ResourceException;
import mm19.game.board.Position;
import mm19.game.player.Player;
import mm19.game.ships.DestroyerShip;
import mm19.game.ships.MainShip;
import mm19.game.ships.PilotShip;
import mm19.game.ships.Ship;
import mm19.server.API;
import mm19.server.ShipActionResult;
import mm19.server.ShipData;


/**
 * @author mm19
 *
 * This will put all the pieces of the game together, and actually make things run.
 */
public class Engine{

    //TODO Create an enum of these ids in appropriate classes
    public static final String SHOOT = "F";
    public static final String BURST_SHOT = "BS";
    public static final String SONAR = "S";
    public static final String MOVE_HORIZONTAL = "MH";
    public static final String MOVE_VERTICAL = "MV";

    public static final String DESTROYER_SHIP = "D";
    public static final String MAIN_SHIP = "M";
    public static final String PILOT_SHIP = "P";

    public static final int TURN_LIMIT = 10000;
    public static final int TIME_LIMIT = 10;

	private Player[] players;
	private String[] playerTokens;
	private int turn = 0;
	private Timer time;

	
	/**
	 * the constructor is called by the API to start the game.
	 */
    public Engine(){
    	players = new Player[Constants.PLAYER_COUNT];
        playerTokens = new String[Constants.PLAYER_COUNT];
        for (int i = 0; i < Constants.PLAYER_COUNT; i++) {
            players[i] = null;
            playerTokens[i] = "";
        }

    	turn = 0;
    }

    public static int getOpponentID(int playerID) {
        //Note: If we ever want to truly generalize the number of players, then dead players should not be opponents.
        return (playerID + 1) % Constants.PLAYER_COUNT;
    }

    /**
     * Helper for handling the exceptions thrown by the Ability class
     * @param ee The EngineException thrown
     * @param playerID The current player's id
     * @param action The action attempted when the exception was thrown.
     * @param turnResults The results object to update
     */
    private void handleEngineException(EngineException ee, int playerID, Action action, ArrayList<ShipActionResult> turnResults) {
        if(ee instanceof InputException) {
            turnResults.add(new ShipActionResult(action.shipID, "I"));
            API.writePlayerError(playerID, ee.getMessage());
        } else if (ee instanceof ResourceException) {
            turnResults.add(new ShipActionResult(action.shipID, "R"));
            API.writePlayerError(playerID, ee.getMessage());
        }
    }
	
    /**
	 * This function sets up the player's pieces on the board as specified
	 * And returns the playerID to the server so that it can refer back to it
	 * returns -1 on bad input
	 */
	public int playerSet(ArrayList<ShipData> shipDatas, String playerToken){
		
		ArrayList<Ship> ships = new ArrayList<Ship>();
		ArrayList<Position> positions = new ArrayList<Position>();
		
		Ship tempShip;
		Position tempPos;
		String tempType;
		
		for(int i = 0; i < Math.min(shipDatas.size(), Constants.MAX_SHIPS); i++){
			tempType = shipDatas.get(i).type;
			tempShip = null;
			if(tempType.equals(DESTROYER_SHIP)){
				tempShip = new DestroyerShip();
			}else if(tempType.equals(MAIN_SHIP)){
				tempShip = new MainShip();
			}else if(tempType.equals(PILOT_SHIP)){
				tempShip = new PilotShip();
			}
			if(tempShip != null){
				if(shipDatas.get(i).orientation.equals("H")){
					tempPos = new Position(shipDatas.get(i).xCoord, 
							shipDatas.get(i).yCoord, 
							Position.Orientation.HORIZONTAL);
				}else{
					tempPos = new Position(shipDatas.get(i).xCoord, 
							shipDatas.get(i).yCoord, 
							Position.Orientation.VERTICAL);
				}
				
				ships.add(tempShip);
				positions.add(tempPos); 
			} else{
				API.writePlayerError(turn%2, "Unable to initialize ship "+i+" to type "+shipDatas.get(i).type);
			}
		}
		if(ships.size() < shipDatas.size()) {
			API.writePlayerResponseCode(turn%2);
			return -1;
		}
		
		Player player=new Player(Constants.STARTING_RESOURCES);
		
		boolean setupShips = Ability.setupBoard(player, ships, positions);
		
		if (!(setupShips && player.isAlive())) {
			API.writePlayerError(turn%2, "Unable to setup ships due to bad positions");
			API.writePlayerResponseCode(turn%2);
			return -1;
			}
		if(players[0] == null) {
			players[0] = player;
		}
		else if (players[1] == null) {
			players[1] = player;
		}
		else throw new RuntimeException("too many players!");
		
		playerTokens[player.getPlayerID()] = playerToken;
		
		ArrayList<ShipData> data = getShipData(player);
		
		
		API.writePlayerShips(player.getPlayerID(), data);
		API.writePlayerResources(player.getPlayerID(), player.getResources());
		API.writePlayerResponseCode(player.getPlayerID());
		
		turn++;
		if(turn > 1){
			time = new Timer();
			time.schedule(new Timeout(this), TIME_LIMIT *1000);
		}
		return player.getPlayerID();
	}
	
	/**
	 * At the start of their turn, they receive resources
	 * This function attempts all of the player's chosen actions for the turn
	 * Afterwards, it tells the API to send the data back
	 */
	public boolean playerTurn(String playerToken, ArrayList<Action> actions){
		//Cancel the timeout
		time.cancel();
		time.purge();
		
		//Check for valid playerID
		int playerID;
		if(playerTokens[0].equals(playerToken)) {
			playerID = 0;
		} else {
			playerID = 1;
		}
		if(playerID != turn % 2 ){
			API.writePlayerError(playerID, "It is not your turn!");
			API.writePlayerResponseCode(playerID);
			return false;
		}
		Player player = null;
		Player otherPlayer = null;
		if(players[0].getPlayerID() == playerID){
			player = players[0];
			otherPlayer = players[1];
		}
		else if(players[1].getPlayerID() == playerID){
			player = players[1];
			otherPlayer = players[0];
		}
		
		Ability.gatherResources(player);
		
		ArrayList<ShipActionResult> turnResults = new ArrayList<ShipActionResult>();
		ArrayList<HitReport> hits = new ArrayList<HitReport>();
		ArrayList<HitReport> opponentHits= new ArrayList<HitReport>();
		ArrayList<SonarReport> pings = new ArrayList<SonarReport>();
		
		for(Action action: actions){
			if(action.actionID.equals(SHOOT)) {
				try{
					HitReport hitResponse = Ability.shoot(player, otherPlayer, action.shipID, action.actionXVar, action.actionYVar);
					turnResults.add(new ShipActionResult(action.shipID, "S"));
					hits.add(hitResponse);
					opponentHits.add(hitResponse);
				} catch(EngineException ee){
                    handleEngineException(ee, playerID, action, turnResults);
				}
			} else if (action.actionID.equals(BURST_SHOT)) {
				try{
					ArrayList<HitReport> burstResponse = 
			        Ability.burstShot(player, otherPlayer, action.shipID, action.actionXVar, action.actionYVar);
					turnResults.add(new ShipActionResult(action.shipID, "S"));
					for(HitReport hitReport : burstResponse){
						if(hitReport.shotSuccessful){
							opponentHits.add(hitReport);
						}
					}
				} catch(EngineException ee){
                    handleEngineException(ee, playerID, action, turnResults);
                }
			} else if (action.actionID.equals(SONAR)) {
				try{
					ArrayList<SonarReport> sonarResponse = Ability.sonar(player, otherPlayer, action.shipID, action.actionXVar, action.actionYVar);
					turnResults.add(new ShipActionResult(action.shipID, "S"));
					pings.addAll(sonarResponse);
				} catch(EngineException ee){
                    handleEngineException(ee, playerID, action, turnResults);
                }
			} else if (action.actionID.equals(MOVE_HORIZONTAL) || action.actionID.equals(MOVE_VERTICAL)) {
                Position.Orientation orientation;
                if(action.actionID.equals(MOVE_HORIZONTAL)) {
                    orientation = Position.Orientation.HORIZONTAL;
                } else {
                    orientation = Position.Orientation.VERTICAL;
                }

				try{
                    //TODO: Ask why move success (moveResponse) is not relayed to player.
					boolean moveResponse = Ability.move(player, action.shipID, new Position(action.actionXVar, action.actionYVar, orientation));
					turnResults.add(new ShipActionResult(action.shipID, "S"));
				} catch(EngineException ee){
                    handleEngineException(ee, playerID, action, turnResults);
                }
			}
		}
		endofTurn(player, turnResults, hits, opponentHits, pings);
		return true;
	}
	
	/**
	 * This function is called when the player fails to send their turn in a reasonable amount of time.
	 * It calls endofTurn as if a turn was taken, but without doing anything else
	 */
	public void timeout(){
		System.out.println("timeout!");
        //TODO: Get rid of magic numbers.
		int currPlayerID = turn%2;
		int opponentID = (turn+1)%2;
		Player p = players[turn % 2];
		endofTurn(p, new ArrayList<ShipActionResult>(), new ArrayList<HitReport>(), new ArrayList<HitReport>(), new ArrayList<SonarReport>());
		
		API.sendTurn(currPlayerID);
	}
	
	/**
	 * This function will check for victory conditions
	 * Then return to the player the results
	 * TODO This function seems very broken, and needs to be gone over again very carefully.
	 * @param results
	 * @param hits
	 * @param sonar
	 */
	public void endofTurn(Player player, ArrayList<ShipActionResult> results, ArrayList<HitReport> hits, ArrayList<HitReport> opponentHits, ArrayList<SonarReport> sonar){
        //TODO: generalize...
		if(!players[0].isAlive() && !players[1].isAlive()){
			API.hasWon(Ability.breakTie(players[0], players[1]).getPlayerID());
		} else if(!players[0].isAlive()){
			//Player 2 wins
			System.out.println("P2 wins!");
			API.hasWon(players[0].getPlayerID());
		} else if(!players[1].isAlive()){
			//Player 1 wins
			System.out.println("P1 wins!");
			API.hasWon(players[1].getPlayerID());
		} else if(turn > TURN_LIMIT){
			//Tie game, break the tie
			System.out.println("Tie!");
			API.hasWon(Ability.breakTie(players[0], players[1]).getPlayerID());
			
		} else{
			//Send data to both players
			int currPlayerID, opponentID;
			Player opponent;
			if(players[0].getPlayerID() == player.getPlayerID()){
				currPlayerID = 0;
				opponentID = 1;
				opponent = players[1];
			} else{
				currPlayerID = 1;
				opponentID = 0;
				opponent = players[0];
			}
			//reset player special
			Ability.resetAbilityStates(players[currPlayerID]);
			ArrayList<ShipData> data = new ArrayList<ShipData>();
			
            Position tempPos;
            String tempType;
            ArrayList<Ship> ships = opponent.getBoard().getShips();
			for(Ship ship : ships){
				tempType = null;
				if(ship instanceof DestroyerShip){
					tempType = DESTROYER_SHIP;
				}else if(ship instanceof MainShip){
					tempType = MAIN_SHIP;
				}else if(ship instanceof PilotShip){
					tempType = PILOT_SHIP;
				}
				String tempOrient = "";
				if(tempType != null){
					if(opponent.getBoard().getShipPosition(ship.getID()).orientation == Position.Orientation.HORIZONTAL){
						tempOrient = "H";
					}else{
						tempOrient = "V";
					}
					tempPos = opponent.getBoard().getShipPosition(ship.getID());

                    //TODO: Where does data get used??
					data.add(new ShipData(ship.getHealth(), ship.getID(), tempType, tempPos.x, tempPos.y, tempOrient));
				}
			}
			
			ArrayList<SonarReport> opponentSonar = new ArrayList<SonarReport>();
			for(SonarReport sr : sonar) {
				opponentSonar.add(new SonarReport(-1, sr.ship));
			}
			
			// Formulate the server response for the current player's turn.
			API.writePlayerResponseCode(currPlayerID);
			API.writePlayerResources(currPlayerID, players[currPlayerID].getResources());
			API.writePlayerShips(currPlayerID, getShipData(players[currPlayerID]));
			API.writePlayerResults(currPlayerID, results);
			API.writePlayerPings(currPlayerID, sonar);
			API.writePlayerHits(currPlayerID, hits);
			
			// Send some info to the other player!
			API.writePlayerShips(opponentID, getShipData(players[opponentID]));
			API.writePlayerHits(opponentID, opponentHits);
			API.writePlayerPings(opponentID, opponentSonar);
			
			turn++;
			
			//Start the timer for the next turn
			time = new Timer();
			time.schedule(new Timeout(this), TIME_LIMIT *1000);
		}
	}
	
	private ArrayList<ShipData> getShipData(Player p) {
		//TODO Use a better name than 'p'
		ArrayList<ShipData> data = new ArrayList<ShipData>();
		ArrayList<Ship> ships = p.getBoard().getShips();
		Ship tempShip;
		Position tempPos;
		String tempType;
		
		for(int i = 0; i < ships.size(); i++){
			
			tempShip = ships.get(i);
			tempType = null;

            //TODO: Add an abstract method to ship class that returns a type abbreviation.
			if(tempShip instanceof DestroyerShip){
				tempType = DESTROYER_SHIP;
			}else if(tempShip instanceof MainShip){
				tempType = MAIN_SHIP;
			}else if(tempShip instanceof PilotShip){
				tempType = PILOT_SHIP;
			}
			
			String temporient = "";
			
			if(tempType != null){
				
				if(p.getBoard().getShipPosition(tempShip.getID()).orientation == Position.Orientation.HORIZONTAL){
					temporient = "H";
				} else{
					temporient = "V";
				}
				tempPos=p.getBoard().getShipPosition(tempShip.getID());
				data.add(new ShipData(tempShip.getHealth(), tempShip.getID(), tempType, tempPos.x, tempPos.y, temporient));
			}
		}
		
		return data;
	}

    //TODO If we aren't going to use these were should remove them
	public int getP1ID() {
		if(players[0] != null) return players[0].getPlayerID();
		return -1;
	}

	public int getP2ID() {
		if(players[1] != null) return players[1].getPlayerID();
		return -1;
	}



}
