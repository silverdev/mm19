package mm19.game;

public class Action {

	// Class variables
	public int actionID;
	public int shipID;
	public int actionXVar;
	public int actionYVar;
	public int actionExtraVar;

	
	// Variable-initializing constructor
	public Action(int shipID, int i, int x, int y, int extra)
	{
		// Initialize variables
		this.shipID = shipID;
		this.actionID = i;
		this.actionXVar = x;
		this.actionYVar = y;
		this.actionExtraVar = extra;
	}
}