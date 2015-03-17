/*
 * For Multi-D !!
 *  This Program generates the Virtual Contours using the gradient assumption.
 *  It also gives the number of optimizations required for getting all the contours.
 *  
 *  -- Pseudocode --
 *  Input : QT's apkt file
 *  		Cost of the contour to be find out.
 *  
 *  Output : Set of points in the plan diagram(and Plans at those points) which covers a specified contour.
 *  
 *  Algorithm :
 *  
 *  Parse the apkt file to get the resolution, selectivities and cost at each point.
 *  Fix Alpha, e -> Error due to discretization..
 *  x_min = minimum Selectivity of the dimension
 *  While x_act >=1
 *  	x_act = x_min
 *  	do binary search to find y_act, s.t Cost(x_act, y_act) = [C-e, C+e]
 *  	if(alpha*x_min is not greater than 1)
 *  		Put the point (alpha*x_min, y_act) to the output list.
 *  	else
 *  		put(1,y_act) to output list.
 *  	x_act = alpha*x_act;
 *  	loop
 *  	
 *  -- Pseudocode Ends --
 *  
 *  Keep track of number of optimizations been called.
 *  Keep track of the total investment benifit
 *  	Call a function which actually gets the contours and gets the total investment.(Brute force case)
 *  
 * */
// ---------------------------------------------------------------------
/*
 * Doubts :
 *  1. Whether the apkt and pkt files are same ??
 *  
 * */



import java.awt.Dimension;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import com.ibm.db2.jcc.b.ac;

import iisc.dsl.picasso.common.ds.DataValues;
import iisc.dsl.picasso.server.ADiagramPacket;


public class GCI3D
{
	static int UNI = 1;
	static int EXP = 2;

	static double err = 0.0;
	static double threshold = 20;

	static double AllPlanCosts[][];
	static int nPlans;
	int plans[];
	double OptimalCost[];
	int totalPlans;
	int dimension;
	static int resolution;
	DataValues[] data;
	static int totalPoints;
	double selectivity[];

	
	//The following parameters has to be set manually for each query
	static String apktPath;
	static String qtName ;
	static String varyingJoins;
	static double JS_multiplier [];
	static String query;
	static String cardinalityPath;

	double  minIndex [];
	double  maxIndex [];
	static boolean MSOCalculation = true;
	static boolean randomPredicateOrder = false;
	
	//Settings: 
	static int sel_distribution; 
	static boolean FROM_CLAUSE = true;
	static Connection conn = null;

	static ArrayList<Integer> remainingDim;
	static ArrayList<ArrayList<Integer>> allPermutations = new ArrayList<ArrayList<Integer>>();
	static ArrayList<point_generic> all_contour_points = new ArrayList<point_generic>();
	static ArrayList<Integer> learntDim = new ArrayList<Integer>();
	static HashMap<Integer,Integer> learntDimIndices = new HashMap<Integer,Integer>();
	static HashMap<Integer,ArrayList<point_generic>> ContourPointsMap = new HashMap<Integer,ArrayList<point_generic>>();

	static double learning_cost = 0;
	static double oneDimCost = 0;
	static int no_executions = 0;
	static int no_repeat_executions = 0;
	static int max_no_executions = 0;
	static int max_no_repeat_executions = 0;

	static boolean [] already_visited;  

	double[] actual_sel;

	//for ASO calculation 
	static double planCount[], planRelativeArea[];
	static float picsel[], locationWeight[];

	static double areaSpace =0,totalEstimatedArea = 0;


	 
	 
	public static void main(String args[]) throws IOException, SQLException
	{
		

		GCI3D obj = new GCI3D();

		obj.loadPropertiesFile();
		String pktPath = apktPath + qtName + ".apkt" ;
		System.out.println("Query Template: "+qtName);
		
		
		ADiagramPacket gdp = obj.getGDP(new File(pktPath));
		
		CostGreedy cg = new CostGreedy();
//		cg.run(threshold, gdp,apktPath);
//		ADiagramPacket reducedgdp = cg.cgFpc(threshold, gdp,apktPath);

		//Populate the OptimalCost Matrix.
		obj.readpkt(gdp);

		//Populate the selectivity Matrix.
		obj.loadSelectivity();
		obj.loadPropertiesFile();
		int i;
		double h_cost = obj.getOptimalCost(totalPoints-1);
		double min_cost = obj.getOptimalCost(0);
		double ratio = h_cost/min_cost;
		assert (h_cost >= min_cost) : "maximum cost is less than the minimum cost";
		System.out.println("the ratio of C_max/c_min is "+ratio);
		
		i = 1;
		
		//to generate contours
		remainingDim.clear(); 
		for(int d=0;d<obj.dimension;d++){
			remainingDim.add(d);
		}
		
		learntDim.clear();
		learntDimIndices.clear();
		double cost = obj.getOptimalCost(0);
		getAllPermuations(remainingDim,0);
		assert (allPermutations.size() == obj.factorial(obj.dimension)) : "all the permutations are not generated";
		
		while(cost < 2*h_cost)
		{
			if(cost>h_cost)
				cost = h_cost;
			System.out.println("---------------------------------------------------------------------------------------------\n");
			System.out.println("Contour "+i+" cost : "+cost+"\n");
			all_contour_points.clear();
			for(ArrayList<Integer> order:allPermutations){
				System.out.println("Entering the order"+order);
				learntDim.clear();
				learntDimIndices.clear();
				obj.getContourPoints(order,cost);
			}
			//Settings
			//writeContourPointstoFile(i);
			int size_of_contour = all_contour_points.size();
			ContourPointsMap.put(i, new ArrayList<point_generic>(all_contour_points)); //storing the contour points
			System.out.println("Size of contour"+size_of_contour );
				cost = cost*2;
				i = i+1;
		}
	
		/*
		 * Setting up the DB connection to Postgres/TPCH Benchmark. 
		 */
		try{
			Class.forName("org.postgresql.Driver");

			//Settings
			conn = DriverManager
					.getConnection("jdbc:postgresql://localhost:5431/tpch-ai",
							"sa", "database");
			 System.out.println("Opened database successfully");
		}
		catch ( Exception e ) {
			System.err.println( e.getClass().getName()+": "+ e.getMessage() );

		}
		/*
		 * running the spillBound and plan bouquet algorithm 
		 */
		double MSO =0, ASO = 0,anshASO = 0,SO=0,MaxHarm=-1*Double.MAX_VALUE,Harm=Double.MIN_VALUE;
		obj.getPlanCountArray();
		//int max_point = 0; /*to not execute the spillBound algorithm*/
		int max_point = 1; /*to execute a specific q_a */
		//Settings
		if(MSOCalculation)
		//if(false)
			max_point = totalPoints;
		double[] subOpt = new double[max_point];
	  for (int  j = 0; j < max_point ; j++)
	  {
		System.out.println("Entering loop "+j);

		//initialization for every loop
		double algo_cost =0;
		SO =0;
		cost = obj.getOptimalCost(0);
		obj.intialize(j);
		int[] index = obj.getCoordinates(obj.dimension, obj.resolution, j);
//		if(index[0]%5 !=0 || index[1]%5!=0)
//			continue;
		//Settings:
//		obj.actual_sel[0] = 0.02;obj.actual_sel[1] = 0.99;obj.actual_sel[2] = 0.1;obj.actual_sel[3] = 0.99; /*uncomment for single execution*/
		
		for(int d=0;d<obj.dimension;d++) obj.actual_sel[d] = obj.findNearestSelectivity(obj.actual_sel[d]);
		//----------------------------------------------------------
		i =1;
		while(i<=ContourPointsMap.size() && !obj.remainingDim.isEmpty())
		{	

			if(cost<(double)10000){
				cost *= 2;
				i++;
				continue;
			}
			if(cost>h_cost)
				cost=h_cost;
			System.out.println("---------------------------------------------------------------------------------------------\n");
			System.out.println("Contour "+i+" cost : "+cost+"\n");
			int prev = obj.remainingDim.size();
			
			if(prev==1){
				obj.oneDimensionSearch(i,cost);
				learning_cost = oneDimCost;
			}
			else
				obj.spillBoundAlgo(i,cg);
			int present = obj.remainingDim.size();
			if(present < prev - 1 || present > prev)
				System.out.println("ERROR");
			
			algo_cost = algo_cost+ (learning_cost);
			if(present == prev ){    // just to see whether any dimension was learnt
									// if no dimension is learnt then move on to the next contour
				
				/*
				 * capturing the properties of the query execution
				 */
				System.out.println("In Contour "+i+" has the following details");
				System.out.println("No of executions is "+no_executions);
				System.out.println("No of repeat moves is "+no_repeat_executions);
				if(no_executions>max_no_executions)
					max_no_executions = no_executions;
				if(no_repeat_executions > max_no_repeat_executions)
					max_no_repeat_executions = no_repeat_executions;
				
				
				cost = cost*2;  
				i = i+1;
				/*
				 * initialize the repeat moves and exections for the next contour
				 */
				for(int d=0;d<obj.dimension;d++){
					already_visited[d] = false;
					no_executions = 0;
					no_repeat_executions =0;
				}
					
			}
			
			System.out.println("---------------------------------------------------------------------------------------------\n");

		}  //end of while
		
		/*
		 * printing the actual selectivity
		 */
		System.out.print("\nThe actual selectivity is original \t");
		for(int d=0;d<obj.dimension;d++) 
			System.out.print(obj.actual_sel[d]+",");
		
		/*
		 * storing the index of the actual selectivities. Using this printing the
		 * index (an approximation) of actual selectivities and its cost
		 */
		int [] index_actual_sel = new int[obj.dimension]; 
		for(int d=0;d<obj.dimension;d++) index_actual_sel[d] = obj.findNearestPoint(obj.actual_sel[d]);
		
		System.out.print("\nCost of actual_sel ="+obj.cost_generic(index_actual_sel)+" at ");
		for(int d=0;d<obj.dimension;d++) System.out.print(index_actual_sel[d]+",");

		SO = (algo_cost/obj.cost_generic(index_actual_sel));
		subOpt[j] = SO;
		Harm = obj.maxHarmCalculation(j, SO);
		if(Harm > MaxHarm)
			MaxHarm = Harm;
		ASO += SO;
		anshASO += SO*locationWeight[j];
		if(SO>MSO)
			MSO = SO;
		System.out.println("\nSpillBound The SubOptimaility  is "+SO);
		System.out.println("\nSpillBound Harm  is "+SO);
	  } //end of for
	  //Settings
	  	obj.writeSuboptToFile(subOpt, apktPath);
	  	conn.close();
	  	System.out.println("SpillBound The MaxSubOptimaility  is "+MSO);
	  	System.out.println("SpillBound The MaxHarm  is "+MaxHarm);
	  	System.out.println("SpillBound Anshuman average Suboptimality is "+(double)anshASO/max_point);
		System.out.println("SpillBound The AverageSubOptimaility  is "+(double)ASO/max_point);

	}
	
	 private int factorial(int num) {

		 int factorial = 1;
		for(int i=num;i>=1;i--){
			factorial *= i; 
		}
		return factorial;
	}

	public void writeSuboptToFile(double[] subOpt,String path) throws IOException {

       File file = new File(path+"spillBound_"+"suboptimality"+".txt");
	    if (!file.exists()) {
	        file.createNewFile();
	    }
	    FileWriter writer = new FileWriter(file, false);
	    PrintWriter pw = new PrintWriter(writer);

	    
	    for(int loc=0;loc<totalPoints;loc++){
	    	int[] index = getCoordinates(dimension, resolution, loc);
	    	for(int d=0;d<dimension;d++){
	    		pw.print(index[d]+",");
	    	}
	    	pw.print("\t is "+subOpt[loc]+"\n");
	    }
//		for(int i =0;i<resolution;i++){
//			for(int j=0;j<resolution;j++){
//				//Settings
//				for(int k=0;k<resolution;k++){
//				//if(i%5==0 && j%5==0){
//					int [] index = new int[3];
//					index[0] = i;
//					index[1] = j;
//					index[2] = k;
//					int ind = getIndex(index, resolution);
//					if(j!=0)
//						pw.print("\t"+subOpt[ind]);
//					else
//						pw.print(subOpt[ind]);
//				//}
//				}	
//			}
//			//if(i%2==0)
//				//pw.print("\n");
//		}
		pw.close();
		writer.close();
		
	}

	
	public void oneDimensionSearch(int contour_no, double cost) {

		String funName = "oneDimensionSearch";
		/*
		 * just sanity check for findNearestPoint and findNearestSelectivity
		 */
		if (findNearestSelectivity(actual_sel[0]) != findNearestSelectivity(findNearestSelectivity(actual_sel[0]))) 
			 System.out.println(" findNearSelectivity" + " Error");
		if (findNearestPoint(actual_sel[0]) != findNearestPoint(selectivity[findNearestPoint(actual_sel[0])])) 
			System.out.println(" findNearPoint" + " Error");
		
		//newly added code Feb28; 8:00 pm
		if(cost_generic(convertSelectivitytoIndex(actual_sel)) > 2*cost){
			oneDimCost = cost;
			return;
		}
			
		oneDimCost = 0;//initialization
		assert (remainingDim.size() == 1): funName+": more than one dimension left";
		int [] arr = new int[dimension];
		int remDim = -1;
		double sel_max = -1;
		for(int d=0;d<dimension;d++){
			if(remainingDim.contains(d))
				remDim = d;
			else
				arr[d] = findNearestPoint(actual_sel[d]);
		}
		
		//TODO: pick the sel_min in the contour in 1D
		for(int c=0;c< ContourPointsMap.get(contour_no).size();c++){

			point_generic p = ContourPointsMap.get(contour_no).get(c);

			if(inFeasibleRegion(convertIndextoSelectivity(p.get_point_Index()))){
				Integer learning_dim = new Integer(p.getLearningDimension());
				assert (learning_dim.intValue() == remDim) : funName+": ERROR plan's learning dimension not matching with remaining dimension";
				if(remainingDim.contains(learning_dim)){
					if(selectivity[p.get_dimension(learning_dim.intValue())] > sel_max){
						sel_max = selectivity[p.get_dimension(learning_dim.intValue())];
						
						//oneDimCost = p.get_cost();
						oneDimCost = cost;
						if(sel_max >= actual_sel[remDim]){
							/*
							 * set it to plan's cost at q_a
							 */
							int fpc_plan = p.get_plan_no();
							int [] int_actual_sel = new int[dimension];
							for(int d=0;d<dimension;d++)
								int_actual_sel[d] = findNearestPoint(actual_sel[d]);
							if(fpc_cost_generic(int_actual_sel, fpc_plan)<oneDimCost)
								oneDimCost = fpc_cost_generic(int_actual_sel, fpc_plan);
							if(cost_generic(int_actual_sel)> oneDimCost)
								oneDimCost = cost_generic(int_actual_sel);
							System.out.println(funName+" learnt "+ remDim+" dimension completely");
							remainingDim.remove(remainingDim.indexOf(remDim));
							System.out.println(funName+" Sel_max = "+sel_max+" and cost is "+oneDimCost);
							return; //done if the sel_max is greater than actual selectivity
						}
							
					}
				}
			}
		}		
		if(sel_max == (double)-1){
			arr[remDim] = 0;
			if(cost_generic(arr) > cost){
				oneDimCost = 0;
				return;
			}
		}
		if(sel_max == (double)-1){
			arr[remDim] = resolution-1;   //TODO is it okay to resolution -1 in 3D or higher
			sel_max = selectivity[resolution-1];
			/*
			 * use the fpc_cost at q_a for oneDimCost
			 */
			int fpcplan = getPlanNumber_generic(arr);
			int [] int_actual_sel = new int[dimension];
			for(int d=0;d<dimension;d++)
				int_actual_sel[d] = findNearestPoint(actual_sel[d]);
			oneDimCost = fpc_cost_generic(int_actual_sel, fpcplan);
			assert (oneDimCost<=2*cost) :funName+": oneDimCost is not less than 2*cost when setting to resolution-1";
		}
		
		/*
		 * does not come here
		 */
		if(sel_max >= actual_sel[remDim]){
			System.out.println(funName+" learnt "+ remDim+" dimension completely");
			remainingDim.remove(remainingDim.indexOf(remDim));
		}
		System.out.println(funName+" Sel_max = "+sel_max+" and cost is "+oneDimCost);

	}
	
private void spillBoundAlgo(int contour_no, CostGreedy cg) throws IOException {

	String funName = "spillBoundAlgo"; 
	Set<Integer> unique_plans = new HashSet();
	
	System.out.println("\nContour number ="+contour_no);
	//----------------------------------- Generate Brute Force..
	
	int i;

		//declaration and initialization of variables
		learning_cost = 0;
		oneDimCost = 0;
		double max_cost=0, min_cost = OptimalCost[totalPoints-1]+1;
		double [] sel_max = new double[dimension];
		
		
		/*
		 * to store the max selectivity each dimension  can learn in a contour
		 */
		for(int d=0;d<dimension;d++) sel_max[d] = -1;
		
		/*
		 * store the plan/point corresponding to the sel_max locations in a hashmap
		 * for which the key is the dimension
		 */
		HashMap<Integer,point_generic> points_max = new HashMap<Integer,point_generic>();
		//end of declaration of variables
		int currentContourPoints = 0;
		
		for(int c=0; c <ContourPointsMap.get(contour_no).size();c++){
			
			point_generic p = ContourPointsMap.get(contour_no).get(c);
			
			/*
			 * update the max and the min cost seen for this contour
			 * also update the unique_plans for the contour
			 */
			unique_plans.add(p.get_plan_no());
			if(p.get_cost()>max_cost)
				max_cost = p.get_cost();
			if(p.get_cost()<min_cost)
				min_cost = p.get_cost();
			
			
			//Settings: for higher just see if you want to comment this
		if(inFeasibleRegion(convertIndextoSelectivity(p.get_point_Index()))){
			currentContourPoints ++;
			Integer learning_dim = new Integer(p.getLearningDimension());
			if(remainingDim.contains(learning_dim)){
				if(selectivity[p.get_dimension(learning_dim.intValue())] > sel_max[learning_dim.intValue()]){
					if(points_max.containsKey(learning_dim))
						points_max.remove(learning_dim);
					points_max.put(learning_dim, p);
					sel_max[learning_dim.intValue()] = selectivity[p.get_dimension(learning_dim.intValue())]; 	
				}
			}
			else{
				System.out.println(funName+": contains the learning dimension which is already learnt");
			}
		   } //end for inFeasibleRegion
		}
		//
		/*
		 * it might happen that due to grid issues that even though q_a lies below a
		 * contour, the contour points could be empty. Hence we put a check condition
		 * that if the cost of the contour is greater than cost(q_a) then add
		 * atleast one point which is the max for all the remaining dimensions
		 */
		double q_a_cost = cost_generic(convertSelectivitytoIndex(actual_sel));
		double c_min = getOptimalCost(0);
		//&& (Math.pow(2, contour_no-1)*c_min <= q_a_cost)
		if(currentContourPoints  ==0) {
			if((Math.pow(2, contour_no)*c_min >= q_a_cost)){
				int [] arr = new int[dimension];	
				//update the learnt dimensions selectivity
				for(int d=0;d<dimension;d++){
					if(remainingDim.contains(d))
						arr[d] = resolution-1;
					else 
						arr[d] = findNearestPoint(actual_sel[d]);
				}
				if(cost_generic(arr)>Math.pow(2, contour_no)*c_min)
					System.out.println(funName+" ERROR from the boundary point");
				else {
					point_generic p = new point_generic(arr, getPlanNumber_generic(arr), cost_generic(arr), remainingDim);
					ContourPointsMap.get(contour_no).add(p);
					max_cost = min_cost = p.get_cost();
					unique_plans.add(p.get_plan_no());
					p.reloadOrderList(remainingDim);
					int learning_dim = p.getLearningDimension();
					sel_max[learning_dim] = selectivity[arr[learning_dim]];
					points_max.put(new Integer(learning_dim),p );
				}

			}
		}	
		//TODO put in an assert saying that the same plan cannot be part of sel_max 
		// of different dimensions
		System.out.print("Max cost = "+max_cost+" Min cost = "+min_cost+" ");
		System.out.println("with Number of Unique plans = "+unique_plans.size());
		
		for(int d=0;d<dimension;d++)
			System.out.println(d+"_max : "+sel_max[d]);
		
		System.out.print("MaxIndex = ");
		for(int d=0;d<dimension;d++)
			System.out.print(maxIndex[d]+",");
		
		System.out.print("\nMinIndex = ");	
		for(int d=0;d<dimension;d++)
			System.out.print(minIndex[d]+",");
		System.out.println();
		for(int d=0;d<dimension;d++){
			
			if(remainingDim.contains(d))
			{				
				if(sel_max[d] != (double)-1){  //TODO is type casting Okay?: Ans: It is fine
					
					/*
					 * update the number of execution and repeat steps here
					 */
					no_executions ++;
					already_visited[d] = true;
					if(already_visited[d]==true)
						no_repeat_executions++;
					
					double sel = 0;
					point_generic p = points_max.get(new Integer(d));
					//sel = Simulated_Spilling(max_x_plan, cg_obj, 0, cur_val);
					if(sel_max[d] <= sel)
						sel_max[d] = sel;  
					sel = getLearntSelectivity(d,p.get_plan_no(),(Math.pow(2, contour_no-1)*getOptimalCost(0)), p);
					if(sel_max[d]<=sel)
						sel_max[d] = sel;
					else
						System.out.println("GetLeantSelectivity: postgres selectivity is less");
					 
//					File file = new File(cardinalityPath+"spill_cost");
//					FileReader fr = new FileReader(file);
//					BufferedReader br = new BufferedReader(fr);
//					learning_cost += Double.parseDouble(br.readLine());
//					System.out.println("Cost of the spilled execution is "+learning_cost);
//					br.close();
//					fr.close();

					if(sel_max[d]>=actual_sel[d]){
						System.out.print("\n Plan "+p.get_plan_no()+" executed at ");
						for(int m=0;m<dimension;m++) System.out.print(p.get_dimension(m)+",");
						System.out.println(" and learnt "+d+" dimension completely");
						minIndex[d] = maxIndex[d] = findNearestSelectivity(actual_sel[d]);
						remainingDim.remove(remainingDim.indexOf(d));
						removeDimensionFromContourPoints(d);
						return;
					}

					System.out.print("\n Plan "+p.get_plan_no()+" executed at ");
					for(int m=0;m<dimension;m++) System.out.print(p.get_dimension(m)+",");
					System.out.println(" and learnt "+sel_max[d]+" selectivity for "+d+"dimension");
					//assert()
				}
			}


		}
		
	/* should this come here or at the end of the earlier for loop?
	*  Ans: As per the theory algorithm, this should come here only since there is no pruning while executing intra contour plans
	*/   	
	for(int d=0;d<dimension;d++){
		if(remainingDim.contains(d) &&  sel_max[d]!=(double)-1 && findNearestSelectivity(sel_max[d])<findNearestSelectivity(actual_sel[d]))
			if(sel_max[d]>minIndex[d])
				minIndex[d] = sel_max[d];
	}
	
}
private void removeDimensionFromContourPoints(int d) {
	String funName = "removeDimensionFromContourPoints";
	//TODO should we have to check for empty contours and points? 
	for(int c=1;c<=ContourPointsMap.size();c++){
		for(point_generic p: ContourPointsMap.get(c)){
			p.remove_dimension(d);
		}
	}
	
}

private double[] convertIndextoSelectivity(int[] point_Index) {
	
	String funName = "convertIndextoSelectivity";
	
	double [] point_selec = new double[point_Index.length];
	assert (point_Index.length == dimension): funName+" ERROR: point index length not matching with dimension"; 
	for(int d=0; d<dimension;d++)
		point_selec[d] = selectivity[point_Index[d]];
	return point_selec;
}

private int[] convertSelectivitytoIndex (double[] point_sel) {
	
	String funName = "convertSelectivitytoIndex";
	
	int [] point_index = new int[point_sel.length];
	assert (point_sel.length == dimension): funName+" ERROR: point index length not matching with dimension"; 
	for(int d=0; d<dimension;d++)
		point_index[d] = findNearestPoint(point_sel[d]);
	return point_index;
}



public double getLearntSelectivity(int dim, int plan, double cost,point_generic p) {

	boolean hashjoinFlag =false;
	String funName = "getLearntSelectivity";
	if(remainingDim.size()==1)
	{
		System.out.println(funName+"ERROR: entering one dimension condition");
		return 0;   //TODO dont do spilling in the 1D case, until we fix the INL case
	}
		
	
	double multiplier = 1,selLearnt = Double.MIN_VALUE,execCost=Double.MIN_VALUE, prevExecCost = Double.MIN_VALUE;
	boolean sel_completely_learnt = false;
	
    Statement stmt = null;

    try {
     
    	stmt = conn.createStatement();
    	//Settings: constants in BinaryTree   
		BinaryTree tree = new BinaryTree(new Vertex(0,-1,null,null),null,null);
		tree.FROM_CLAUSE = FROM_CLAUSE;
		int spill_values [] = tree.getSpillNode(dim,plan); //[0] gives node id of the tree and [1] gives the spill_node for postgres
		int spill_node = spill_values[1];
		
		/*
		 *temp_act_sel to iterate from point p to  until either we exhaust the budget 
		 *or learn the actual selectivity. Note the change. 
		 */
		double[] temp_act_sel = new double[dimension];
		for(int d=0;d<dimension;d++){
			if(dim==d){
				if(selectivity[p.get_dimension(d)] >= actual_sel[d])
					temp_act_sel[d] = actual_sel[d];
				else
					temp_act_sel[d] = selectivity[p.get_dimension(d)];
			}
			else
				temp_act_sel[d] = actual_sel[d];
		}
		
		double budget = cost;   //if this contour is the last for point p set the budget to  point's cost
		if(p.get_cost() <2*cost)
			budget = p.get_cost();
		
		while((temp_act_sel[dim] <= actual_sel[dim]) || (execCost<=budget))
		{	
			stmt.execute("set spill_node = "+ spill_node);
			stmt.execute("set work_mem = '100MB'");
			//NOTE,Settings: 4GB for DS and 1GB for H
			stmt.execute("set effective_cache_size='1GB'");
			//NOTE,Settings: need not set the page cost's
			stmt.execute("set  seq_page_cost = 1");
			stmt.execute("set  random_page_cost=4");
			stmt.execute("set cpu_operator_cost=0.0025");
			stmt.execute("set cpu_index_tuple_cost=0.005");
			stmt.execute("set cpu_tuple_cost=0.01");	
			stmt.execute("set full_robustness = on");
			stmt.execute("set oneFPCfull_robustness = on");
			stmt.execute("set varyingJoins = "+varyingJoins);

			for(int d=0;d<dimension;d++){
				stmt.execute("set JS_multiplier"+(d+1)+ "= "+ JS_multiplier[d]);
				stmt.execute("set robust_eqjoin_selec"+(d+1)+ "= "+ selectivity[p.get_dimension(d)]);
				stmt.execute("set FPC_JS_multiplier"+(d+1)+ "= "+ JS_multiplier[d]);
				stmt.execute("set FPCrobust_eqjoin_selec"+(d+1)+ "= "+ temp_act_sel[d]);
				// essentially forcing the  plan optimal at (x,y) location to the query having (x_a,y_a) 
				// as selectivities been injected 
			}

			stmt.execute(query);
			//read the selectivity returned
			File file = new File(cardinalityPath+"spill_cost");
			FileReader fr = new FileReader(file);
			BufferedReader br = new BufferedReader(fr);
			//read the selectivity or the info needed for INL
			//--------------------------------------------------------------
			execCost = Double.parseDouble(br.readLine());
			
			String valstring = new String();
			/*
			 * If there are two rows in the file then it is the hash join case. Where the second row is the number of rows
			 * fetched by the predicate at p.getDimension(dim) selectivity 
			 */
			if((valstring = br.readLine()) != null){
				hashjoinFlag = true;
				double rows = Double.parseDouble(valstring);
				double remainingBudget = budget -execCost; 
				
				if(remainingBudget<=0)
					break;
				//assert(newrows>=rows) : funName+" hashjoin case ";
				//0.01 is used since this is the cost taken for outputting every tuple (=cpu_tuple_cost) 
				double  budget_needed= (rows*0.01)*(actual_sel[dim]/selectivity[p.get_dimension(dim)] -1);
				if(budget_needed <=0){
					sel_completely_learnt = true;
					 //already p is at actual_sel[dim]
				}
				else if(budget_needed > remainingBudget){
	
					temp_act_sel[dim] = selectivity[p.get_dimension(dim)] + (actual_sel[dim]- selectivity[p.get_dimension(dim)])*(remainingBudget/budget_needed);
					if(findNearestSelectivity(temp_act_sel[dim])!=temp_act_sel[dim]){
						//move to the lower indexed selectivity
						int idx = findNearestPoint(temp_act_sel[dim]);
						idx --;
						assert(idx>=0) : funName+" index going below 0";
						 temp_act_sel[dim] = selectivity[idx];
					}
					selLearnt = temp_act_sel[dim];
					double extra_budget_consumed = (rows*0.01)*(temp_act_sel[dim]/selectivity[p.get_dimension(dim)] -1);
					prevExecCost = execCost + extra_budget_consumed;
						
				}
				else if(budget_needed <= remainingBudget){
					sel_completely_learnt = true;
					execCost += budget_needed;
				}
				break;
			}
			br.close();
			fr.close();
			//-----------------------------------------------------------------
			/*
			 * this is the case when the current execution exceeds the budget and learnt selectivity is as per the
			 * earlier loop's selectivity of dim. The learnt selectivity is strictly less than actual selectivity. This is 
			 * because if the learnt selectivity (earlier iteration's) is >= than the actual sel, the loop should have finished 
			 * in the earlier iteration itself. This is anyway conservative. 
			 */
			if(execCost>budget) //TODO: should we add a small threshold? may be use the next point's cost
				break;
			/*
			 * this is the case when we learn the actual selectivity since the execCost (subplan's cost) does not 
			 * exceed the plan's total budget
			 */
			if(findNearestPoint(temp_act_sel[dim]) >= findNearestPoint(actual_sel[dim])){
				sel_completely_learnt = true;
				break;
			}
			prevExecCost = execCost;
			
			//adding the code for oneshot learning of predicate
			
			int idx = findNearestPoint(temp_act_sel[dim]);
			idx ++;
			assert(idx<resolution) : funName+" index exceeding resolution";
			temp_act_sel[dim] = selectivity[idx];
		}

    	if(sel_completely_learnt){
			
    		learning_cost += execCost;
    		selLearnt = temp_act_sel[dim];
			System.out.println("Cost of the spilled execution is "+execCost);
    	}
    	else{
    		if(prevExecCost==Double.MIN_VALUE){
    			learning_cost += cost; //just adding the cost of the contour in the while loop zips through in the starting iteration itself
    			prevExecCost = cost; //for the sake of printing
    			selLearnt = selectivity[p.get_dimension(dim)];
    		}
    		else{
    			//prevExecCost = cost;
    			learning_cost += prevExecCost; //actual cost taken for learning selecitvity
    		}
    			
    		System.out.println("Cost of the spilled execution (not sucessful) is "+prevExecCost);
    		int idx = findNearestPoint(temp_act_sel[dim]);
    		if(!hashjoinFlag && idx>0){	
    			idx --;
    			assert(idx>=0): funName+" idx going below 0";
    			selLearnt = selectivity[idx];
    		}
    	}
    }
    catch ( Exception e ) {
    	System.err.println( e.getClass().getName()+": "+ e.getMessage() );

    } 		
    	assert(dim<=dimension) : funName+" dim data structure more dimensions possible";
    	assert(selLearnt<=1):funName+"selectivity learnt is greater than 1!";
    	System.out.println("Postgres: selectivity learnt  "+selLearnt+" with plan number "+plan);
    	return selLearnt; //has to have single line in the file

}
//public double getLearntSelectivityOld(int dim, int plan, double cost,point_generic p) {
//
//	
//	String funName = "getLearntSelectivity";
//	if(remainingDim.size()==1)
//	{
//		System.out.println(funName+"ERROR: entering one dimension condition");
//		return 0;   //TODO dont do spilling in the 1D case, until we fix the INL case
//	}
//		
//	
//	double multiplier = 1,selLearnt = Double.MIN_VALUE,selDim= actual_sel[0];
//	double est_rows=1, rows_learnt=1, outer_rows =1;
//
//	
////	if(dim==0){
////		multiplier = (double)150000/JS_multiplier1;
////		selDim = actual_sel[0];
////	}
////	else {
////		multiplier = (double)1500000/JS_multiplier2;
////		selDim = actual_sel[1];
////	}
//
//
//	if(dim==0){
//	multiplier = (double)150000/JS_multiplier1;
//	selDim = actual_sel[0];
//	}
//else if (dim==1){
//	multiplier = (double)1500000/JS_multiplier2;
//	selDim = actual_sel[1];
//}
//else {
//	selDim = actual_sel[2];
//	multiplier = (double)10000/JS_multiplier2;
//}
//	
//	
//      //Connection c = null;
//       Statement stmt = null;
//       Double val = new Double(0);
//       //String cardinalityPath = "/home/dsladmin/Srinivas/data/PostgresCardinality/";
//       try {
//        
//         stmt = conn.createStatement();
//        //constants in BinaryTree   
// 		BinaryTree tree = new BinaryTree(new Vertex(0,-1,null,null),null,null);
// 		tree.FROM_CLAUSE = FROM_CLAUSE;
// 		int spill_values [] = tree.getSpillNode(dim,plan); //[0] gives node id of the tree and [1] gives the spill_node for postgres
// 		int spill_node = spill_values[1];
//        stmt.execute("set spill_node = "+ spill_node);
//        stmt.execute("set spill_join = 1");
//        stmt.execute("set work_mem = '100MB'");
//        stmt.execute("set effective_cache_size='1GB'");
//        stmt.execute("set  seq_page_cost = 0");
//        stmt.execute("set  random_page_cost=0");
//        stmt.execute("set limit_cost = "+ cost);
//        stmt.execute("set full_robustness = on");
//        stmt.execute("set oneFPCfull_robustness = on");
//        stmt.execute("set varyingJoins = "+varyingJoins);
//        stmt.execute("set JS_multiplier1 = "+ JS_multiplier1);
//        stmt.execute("set JS_multiplier2 = "+ JS_multiplier2);
//        stmt.execute("set JS_multiplier3 = "+ JS_multiplier3);
//        stmt.execute("set robust_eqjoin_selec1 = "+ selectivity[p.get_dimension(0)]);
//        stmt.execute("set robust_eqjoin_selec2 = "+ selectivity[p.get_dimension(1)]);
//        stmt.execute("set robust_eqjoin_selec3 = "+ selectivity[p.get_dimension(2)]);
//        stmt.execute("set FPC_JS_multiplier1 = "+ JS_multiplier1);
//        stmt.execute("set FPC_JS_multiplier2 = "+ JS_multiplier2);
//        stmt.execute("set FPC_JS_multiplier3 = "+ JS_multiplier3);
//        stmt.execute("set FPCrobust_eqjoin_selec1 = "+ findNearestSelectivity(actual_sel[0]));
//        stmt.execute("set FPCrobust_eqjoin_selec2 = "+ findNearestSelectivity(actual_sel[1]));
//        stmt.execute("set FPCrobust_eqjoin_selec3 = "+ findNearestSelectivity(actual_sel[2]));
//        // essentially forcing the  plan optimal at (x,y) location to the query having (x_a,y_a) 
//        // as selectivities been injected 
//        
//        stmt.execute(query);
//        //read the selectivity returned
//        File file = new File(cardinalityPath+"spill_cardinality");
//    	FileReader fr = new FileReader(file);
//    	BufferedReader br = new BufferedReader(fr);
//    	
//    	//read the selectivity or the info needed for INL
//    	val = Double.parseDouble(br.readLine());
//    	String est_rows_str;
//    	if((est_rows_str=br.readLine())==null)
//    		selLearnt = val; //this is the Non-INL case
//    	else {
//    		rows_learnt = val; //rows learnt for this budget
//    		est_rows = Double.parseDouble(est_rows_str); //estimated rows of the join node 
//        	outer_rows = Double.parseDouble(br.readLine()); //actual outer rows of the join node
//        	
//        	//selectivity calculations
//        	double inner_rows = (est_rows*multiplier)/(selDim*outer_rows);
//        	val = rows_learnt/(inner_rows*outer_rows);
//    	}
//    	        	
//    	br.close();
//    	fr.close();
//       }
//       catch ( Exception e ) {
//			System.err.println( e.getClass().getName()+": "+ e.getMessage() );
//
//		}
//
//
//    	        	
//    	selLearnt = val * multiplier *(1+err);
//    	assert(selLearnt<=1):"error in multiplier";
//    	selLearnt = findNearestSelectivity(selLearnt);
//    	System.out.println("Postgres: selectivity learnt  "+selLearnt+" with plan number "+plan);
//    	return selLearnt; //has to have single line in the file
//
//}

public void intialize(int location) {

	String funName = "intialize";
	//updating the feasible region
	for(int i=0;i<dimension;i++){
		minIndex[i] =  findNearestSelectivity(0);
		maxIndex[i] = findNearestSelectivity(0.99);
	}
	//updating the remaining dimensions data structure
	remainingDim.clear();
	for(int i=0;i<dimension;i++)
		remainingDim.add(i);
	
	
	learning_cost = 0;
	oneDimCost = 0;
	no_executions = 0;
	no_repeat_executions =0;
	max_no_executions = 0;
	max_no_repeat_executions =0;
	already_visited = new boolean[dimension];
	for(int i =0;i<dimension;i++)
		already_visited[i] = false;
	//updating the actual selectivities for each of the dimensions
	int index[] = getCoordinates(dimension, resolution, location);
	
	actual_sel = new double[dimension];
	for(int i=0;i<dimension;i++){
		actual_sel[i] = selectivity[index[i]];
	}
	
	
	//sanity check conditions
	assert(remainingDim.size() == dimension): funName+"ERROR: mismatch in remaining Dimensions";
	
	/*
	 * reload the order list before the start of  every contour
	 */
	for(int i=1;i<=ContourPointsMap.size();i++){
		for(int j=0;j<ContourPointsMap.get(i).size();j++){
			point_generic p = ContourPointsMap.get(i).get(j);
			p.reloadOrderList(remainingDim);
		}
	}
	
}

	private static void writeContourPointstoFile(int contour_no) {

		try {
	    
//	    String content = "This is the content to write into file";


         File filex = new File("/home/dsladmin/Srinivas/data/others/contours/"+"x"+contour_no+".txt"); 
         File filey = new File("/home/dsladmin/Srinivas/data/others/contours/"+"y"+contour_no+".txt"); 
//         File filez = new File("/home/dsladmin/Srinivas/data/others/contours/"+"z"+contour_no+".txt"); 
	    // if file doesn't exists, then create it
	    if (!filex.exists()) {
	        filex.createNewFile();
	    }
	    if (!filey.exists()) {
	        filey.createNewFile();
	    }
//	    if (!filez.exists()) {
//	        filez.createNewFile();
//	    }

	    FileWriter writerax = new FileWriter(filex, false);
	    FileWriter writeray = new FileWriter(filey, false);
//	    FileWriter writeraz = new FileWriter(filez, false);
	    
	    PrintWriter pwax = new PrintWriter(writerax);
	    PrintWriter pway = new PrintWriter(writeray);
//	    PrintWriter pwaz = new PrintWriter(writeraz);
	    //Take iterator over the list
	    for(point_generic p : all_contour_points) {
		    //        System.out.println(p.getX()+":"+p.getY()+": Plan ="+p.p_no);
	   	 pwax.print((int)p.get_dimension(0) + "\t");
	   	 pway.print((int)p.get_dimension(1)+ "\t");
//	   	pwaz.print((int)p.get_dimension(2)+ "\t");
	   	 
	    }
	    pwax.close();
	    pway.close();
//	    pwaz.close();
	    writerax.close();
	    writeray.close();
//	    writeraz.close();
	    
		} catch (IOException e) {
	    e.printStackTrace();
	}
		
	}


	private static void getAllPermuations(ArrayList<Integer> DimOrder,int k) {


		if (k == DimOrder.size()) 
		{	ArrayList<Integer> tempList = new ArrayList<Integer>();
			for (int i = 0; i <  DimOrder.size(); i++) 
			{
				tempList.add(DimOrder.get(i));
			}
			allPermutations.add(tempList);
		} 
		else 
		{
			for (int i = k; i < DimOrder.size(); i++) 
			{
				int temp = DimOrder.get(k);
				DimOrder.set(k, DimOrder.get(i));
				DimOrder.set(i,temp);

				getAllPermuations(DimOrder, k + 1);

				temp = DimOrder.get(k);
				DimOrder.set(k, DimOrder.get(i));
				DimOrder.set(i,temp);

			}

		}

	}
	
	


	void getContourPoints(ArrayList<Integer> order,double cost) throws IOException
	{
		String funName = "getContourPoints";
		//learntDim contains the dimensions already learnt (which is null initially)
		//learntDimIndices contains the exact point in the space for the learnt dimensions

		ArrayList<Integer> remainingDimList = new ArrayList<Integer>();
		for(int i=0;i<order.size();i++)
		{
			if(learntDim.contains(order.get(i))!=true)
			{
				remainingDimList.add(order.get(i));
			}			
		}
		
		if(remainingDimList.size()==1 )
		{ 
			int last_dim=-1;
			int [] arr = new int[dimension];
			
			for(int i=0;i<dimension;i++)
			{
				if(learntDim.contains(i))
				{
					arr[i] = learntDimIndices.get(i);
					//System.out.print(arr[i]+",");
				}
				else
					last_dim = i;
			}
			
			assert (learntDim.size() == learntDimIndices.size()) : funName+" : learnt dimension data structures size not matching";
			assert (last_dim>=0 && last_dim<dimension) :funName+ " : index problem ";
			assert (remainingDimList.size() + learntDim.size() == dimension) : funName+" : learnt dimension data structures size not matching";
			
			//Search the whole line and return all the points which fall into the contour
			for(int i=0;i< resolution;i++)
			{
				arr[last_dim] = i;
				double cur_val = cost_generic(arr);
				double targetval = cost;

				if(cur_val == targetval)
				{
					if(!pointAlreadyExist(arr)){ //check if the point already exist
						point_generic p = new point_generic(arr,getPlanNumber_generic(arr),cur_val, remainingDim);
						all_contour_points.add(p);
					}
				}
				else if(i!=0){
					arr[last_dim]--; //in order to look at the cost at lower value on the last index
					double cur_val_l = cost_generic(arr);
					arr[last_dim]++; //restore the index back 
					if( cur_val > targetval  && cur_val_l < targetval ) //NOTE : changed the inequality to strict inequality
					{
						if(!pointAlreadyExist(arr)){ //check if the point already exist
							point_generic p = new point_generic(arr,getPlanNumber_generic(arr), cur_val,remainingDim);
							all_contour_points.add(p);
						}
					}
					
				}
			}


			return;
		}
		
		Integer curDim = remainingDimList.get(0); //index of 0 or size-1 does not matter
		Integer cur_index = resolution -1;
		
		while(cur_index >= 0)
		{
			learntDim.add(curDim);
			learntDimIndices.put(curDim,cur_index);
			getContourPoints(order,cost);
			learntDim.remove(learntDim.indexOf(curDim));
			learntDimIndices.remove(curDim);
			cur_index = cur_index - 1;
		}
		
	}
	
	private boolean pointAlreadyExist(int[] arr) {

		boolean flag = false;
		for(point_generic p: all_contour_points){
			flag = true;
			for(int i=0;i<dimension;i++){
				if(p.get_dimension(i)!= arr[i]){
					flag = false;
					break;
				}
			}
			if(flag==true)
				return true;
		}
		
		return false;
	}


	
	
	
	
	
	
	
// Return the index near to the selecitivity=mid;
	public int findNearestPoint(double mid)
	{
		int i;
		int return_index = 0;
		double diff;
		if(mid >= selectivity[resolution-1])
			return resolution-1;
		for(i=0;i<resolution;i++)
		{
			diff = mid - selectivity[i];
			if(diff <= 0)
			{
				return_index = i;
				break;
			}
		}
		
		/*
		 * sanity check
		 */
		
			
		//System.out.println("return_index="+return_index);
		return return_index;
	}
	

	// Return the selectivity just greater than selecitivity=mid;
		public double findNearestSelectivity(double mid)
		{
			int i;
			int return_index = 0;
			double diff;
			if(mid >= selectivity[resolution-1])
				return selectivity[resolution-1];
			for(i=0;i<resolution;i++)
			{
				diff = mid - selectivity[i];
				if(diff <= 0)
				{
					return_index = i;
					break;
				}
			}
			//System.out.println("return_index="+return_index);
			return selectivity[return_index];
		}

		/*-------------------------------------------------------------------------------------------------------------------------
		 * Populates -->
		 * 	dimension
		 * 	resolution
		 * 	totalPoints
		 * 	OptimalCost[][]
		 * 
		 * */
		void readpkt(ADiagramPacket gdp) throws IOException
		{
			//ADiagramPacket gdp = getGDP(new File(pktPath));
			totalPlans = gdp.getMaxPlanNumber();
			dimension = gdp.getDimension();
			resolution = gdp.getMaxResolution();
			data = gdp.getData();
			totalPoints = (int) Math.pow(resolution, dimension);
			//System.out.println("\nthe total plans are "+totalPlans+" with dimensions "+dimension+" and resolution "+resolution);

			assert (totalPoints==data.length) : "Data length and the resolution didn't match !";

			plans = new int [data.length];
			OptimalCost = new double [data.length]; 
			for (int i = 0;i < data.length;i++)
			{
				this.OptimalCost[i]= data[i].getCost();
				this.plans[i] = data[i].getPlanNumber();
			}

			//To get the number of points for each plan
			int  [] plan_count = new int[totalPlans];
			for(int p=0;p<data.length;p++){
				plan_count[plans[p]]++;
			}
			//printing the above
			for(int p=0;p<plan_count.length;p++){
				System.out.println("Plan "+p+" has "+plan_count[p]+" points");
			}

			/*
			 * Reading the pcst files
			 */
			nPlans = totalPlans;
			AllPlanCosts = new double[nPlans][totalPoints];
			for (int i = 0; i < nPlans; i++) {
				try {

					ObjectInputStream ip = new ObjectInputStream(new FileInputStream(new File(apktPath + i + ".pcst")));
					double[] cost = (double[]) ip.readObject();
					for (int j = 0; j < totalPoints; j++)
					{					
						AllPlanCosts[i][j] = cost[j];
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			 remainingDim = new ArrayList<Integer>();
				for(int i=0;i<dimension;i++)
					remainingDim.add(i);
				minIndex = new double[dimension];
				maxIndex = new double[dimension];

			//writing the optimal cost matirx

			/*	File file = new File("C:\\Lohit\\data\\plans\\"+i+".txt");
			    if (!file.exists()) {
					file.createNewFile();
				}

			    FileWriter writer = new FileWriter(file, false);

			    PrintWriter pw = new PrintWriter(writer);

			for(int i=0;i<resolution;i++){
				for(int j=0;j<resolution;j++){
					int[] indexp = {j,i};
					pw.format("%f\t", OptimalCost[getIndex(indexp, resolution)]);;
				}
				pw.format("\n");
			}
			 */	

		}

	
	 double cost(int x, int y, int z)
	{
		int [] arr = new int [3];
		arr[0] = x;
		arr[1] = y;
		arr[2] = z;
		int index = getIndex(arr,resolution);

		
		return OptimalCost[index];
	}
	 double cost_generic(int arr[])
		{
		 
			int index = getIndex(arr,resolution);

			
			return OptimalCost[index];
		}
	 
	 double fpc_cost_generic(int arr[], int plan)
		{
		 
			int index = getIndex(arr,resolution);

			
			return AllPlanCosts[plan][index];
		}

	//-------------------------------------------------------------------------------------------------------------------
		/*
		 * Populates the selectivity Matrix according to the input given
		 * */
		void loadSelectivity()
		{
			String funName = "loadSelectivity: ";
			System.out.println(funName+" Resolution = "+resolution);
			double sel;
			this.selectivity = new double [resolution];
			
			if(resolution == 10){
				if(sel_distribution == 0){
					
					//This is for TPCH queries 
					selectivity[0] = 0.0005;	selectivity[1] = 0.005;selectivity[2] = 0.01;	selectivity[3] = 0.02;
					selectivity[4] = 0.05;		selectivity[5] = 0.1;	selectivity[6] = 0.2;	selectivity[7] = 0.4;
					selectivity[8] = 0.6;		selectivity[9] = 0.95;                                 // oct - 2012
				}
				else if( sel_distribution ==1){
					
					//This is for TPCDS queries
					selectivity[0] = 0.00005;	selectivity[1] = 0.0005;selectivity[2] = 0.005;	selectivity[3] = 0.02;
					selectivity[4] = 0.05;		selectivity[5] = 0.10;	selectivity[6] = 0.15;	selectivity[7] = 0.25;
					selectivity[8] = 0.50;		selectivity[9] = 0.95;                                 // dec - 2012
				}
				else
					assert (false) : "should not come here";

			}		
			if(resolution == 20){
			selectivity[0] = 0.000005;		selectivity[1] = 0.00005;		selectivity[2] = 0.0005;	selectivity[3] = 0.002;
			selectivity[4] = 0.005;		selectivity[5] = 0.008;		selectivity[6] = 0.01;		selectivity[7] = 0.02;
			selectivity[8] = 0.03;			selectivity[9] = 0.04;			selectivity[10] = 0.05;	selectivity[11] = 0.08;
			selectivity[12] = 0.10; 		selectivity[13] = 0.15;		selectivity[14] = 0.20;	selectivity[15] = 0.30;
			selectivity[16] = 0.40;		selectivity[17] = 0.60;		selectivity[18]=0.80;		selectivity[19] = 0.99;
			}

			if(resolution == 30){
			selectivity[0] = 0.00002;  selectivity[1] = 0.00009;	selectivity[2] = 0.0002;	selectivity[3] = 0.0005;
			selectivity[4] = 0.0007;   selectivity[5] = 0.0010;	selectivity[6] = 0.0014;	selectivity[7] = 0.0019;
			selectivity[8] = 0.0026;	selectivity[9] = 0.0036;	selectivity[10] = 0.0048;	selectivity[11] = 0.0065;
			selectivity[12] = 0.0087;	selectivity[13] = 0.0117;	selectivity[14] = 0.0156;	selectivity[15] = 0.0208;
			selectivity[16] = 0.0278;	selectivity[17] = 0.0370;	selectivity[18] = 0.0493;	selectivity[19] = 0.0657;
			selectivity[20] = 0.0874;	selectivity[21] = 0.1164;	selectivity[22] = 0.1549;	selectivity[23] = 0.2061;
			selectivity[24] = 0.2741;	selectivity[25] = 0.3647;	selectivity[26] = 0.48515;	selectivity[27] = 0.6453;
			selectivity[28] = 0.8583;	selectivity[29] = 0.9950;		
			}
			
			if(resolution==100){
				selectivity[0] = 0.005995; 	selectivity[1] = 0.015985; 	selectivity[2] = 0.025975; 	selectivity[3] = 0.035965; 	selectivity[4] = 0.045955; 	
				selectivity[5] = 0.055945; 	selectivity[6] = 0.065935; 	selectivity[7] = 0.075925; 	selectivity[8] = 0.085915; 	selectivity[9] = 0.095905; 	
				selectivity[10] = 0.105895; 	selectivity[11] = 0.115885; 	selectivity[12] = 0.125875; 	selectivity[13] = 0.135865; 	selectivity[14] = 0.145855; 	
				selectivity[15] = 0.155845; 	selectivity[16] = 0.165835; 	selectivity[17] = 0.175825; 	selectivity[18] = 0.185815; 	selectivity[19] = 0.195805; 	
				selectivity[20] = 0.205795; 	selectivity[21] = 0.215785; 	selectivity[22] = 0.225775; 	selectivity[23] = 0.235765; 	selectivity[24] = 0.245755; 	
				selectivity[25] = 0.255745; 	selectivity[26] = 0.265735; 	selectivity[27] = 0.275725; 	selectivity[28] = 0.285715; 	selectivity[29] = 0.295705; 	
				selectivity[30] = 0.305695; 	selectivity[31] = 0.315685; 	selectivity[32] = 0.325675; 	selectivity[33] = 0.335665; 	selectivity[34] = 0.345655; 	
				selectivity[35] = 0.355645; 	selectivity[36] = 0.365635; 	selectivity[37] = 0.375625; 	selectivity[38] = 0.385615; 	selectivity[39] = 0.395605; 	
				selectivity[40] = 0.405595; 	selectivity[41] = 0.415585; 	selectivity[42] = 0.425575; 	selectivity[43] = 0.435565; 	selectivity[44] = 0.445555; 	
				selectivity[45] = 0.455545; 	selectivity[46] = 0.465535; 	selectivity[47] = 0.475525; 	selectivity[48] = 0.485515; 	selectivity[49] = 0.495505; 	
				selectivity[50] = 0.505495; 	selectivity[51] = 0.515485; 	selectivity[52] = 0.525475; 	selectivity[53] = 0.535465; 	selectivity[54] = 0.545455; 	
				selectivity[55] = 0.555445; 	selectivity[56] = 0.565435; 	selectivity[57] = 0.575425; 	selectivity[58] = 0.585415; 	selectivity[59] = 0.595405; 	
				selectivity[60] = 0.605395; 	selectivity[61] = 0.615385; 	selectivity[62] = 0.625375; 	selectivity[63] = 0.635365; 	selectivity[64] = 0.645355; 	
				selectivity[65] = 0.655345; 	selectivity[66] = 0.665335; 	selectivity[67] = 0.675325; 	selectivity[68] = 0.685315; 	selectivity[69] = 0.695305; 	
				selectivity[70] = 0.705295; 	selectivity[71] = 0.715285; 	selectivity[72] = 0.725275; 	selectivity[73] = 0.735265; 	selectivity[74] = 0.745255; 	
				selectivity[75] = 0.755245; 	selectivity[76] = 0.765235; 	selectivity[77] = 0.775225; 	selectivity[78] = 0.785215; 	selectivity[79] = 0.795205; 	
				selectivity[80] = 0.805195; 	selectivity[81] = 0.815185; 	selectivity[82] = 0.825175; 	selectivity[83] = 0.835165; 	selectivity[84] = 0.845155; 	
				selectivity[85] = 0.855145; 	selectivity[86] = 0.865135; 	selectivity[87] = 0.875125; 	selectivity[88] = 0.885115; 	selectivity[89] = 0.895105; 	
				selectivity[90] = 0.905095; 	selectivity[91] = 0.915085; 	selectivity[92] = 0.925075; 	selectivity[93] = 0.935065; 	selectivity[94] = 0.945055; 	
				selectivity[95] = 0.955045; 	selectivity[96] = 0.965035; 	selectivity[97] = 0.975025; 	selectivity[98] = 0.985015; 	selectivity[99] = 0.995005;
			}
			//the selectivity distribution
			//System.out.println("The selectivity distribution using is ");
//			for(int i=0;i<resolution;i++)
//			System.out.println("\t"+selectivity[i]);
		}
	
	
	private ADiagramPacket getGDP(File file) {
		ADiagramPacket gdp = null;
		FileInputStream fis;
		try {
			fis = new FileInputStream(file);
			ObjectInputStream ois = new ObjectInputStream(fis);
			Object obj = ois.readObject();
			gdp = (ADiagramPacket) obj;
			
			ois.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		return gdp;
	}
	public int[] getCoordinates(int dimensions, int res, int location){
		int [] index = new int[dimensions];

		for(int i=0; i<dimensions; i++){
			index[i] = location % res;

			location /= res;
		}
		return index;
	}
	public int getIndex(int[] index,int res)
	{
		int tmp=0;

		for(int i=index.length-1; i>=0; i--){
			if(index[i] > 0)
				tmp=tmp * res + index[i];
			else
				tmp=tmp * res;
		}

		return tmp;
	}
	double getOptimalCost(int index)
	{
		return this.OptimalCost[index];
	}
	int getPlanNumber(int x, int y, int z)
	{
		 int arr[] = {x,y,z};
		int index = getIndex(arr,resolution);
		return plans[index];
	}
	int getPlanNumber_generic(int arr[])
	{
		int index = getIndex(arr,resolution);
		return plans[index];
	}
	
	
public	int calculatePlanNumber(double x_act, double y_act, double z_act)
	{
		int x,y,z;
		x = findNearestPoint(x_act);
		y = findNearestPoint(y_act);
		z = findNearestPoint(z_act);
		
		return getPlanNumber(x,y,z);
		
	}

 
 public boolean inFeasibleRegion(double [] arr) {
	 	boolean flag = true;
	 	for(int d=0;d<dimension;d++){
	 		if(!(minIndex[d]<=arr[d] && arr[d]<=maxIndex[d])){
	 			flag = false;
	 			break;
	 		}
	 	}
	 	return flag; 			
	}
 
	public void loadPropertiesFile() {

		Properties prop = new Properties();
		InputStream input = null;
	 
		try {
	 
			input = new FileInputStream("./src/Constants.properties");
	 
			// load a properties file
			prop.load(input);
	 
			// get the property value and print it out
			apktPath = prop.getProperty("apktPath");
			qtName = prop.getProperty("qtName");
			varyingJoins = prop.getProperty("varyingJoins");
			
			JS_multiplier = new double[dimension];
			for(int d=0;d<dimension;d++){
				String multiplierStr = new String("JS_multiplier"+(d+1));
				JS_multiplier[d] = Double.parseDouble(prop.getProperty(multiplierStr));
			}
			
			//Settings:Note dont forget to put analyze here
			//query = "explain analyze FPC(\"lineitem\") (\"104949\")  select	supp_nation,	cust_nation,	l_year,	volume from	(select n1.n_name as supp_nation, n2.n_name as cust_nation, 	DATE_PART('YEAR',l_shipdate) as l_year,	l_extendedprice * (1 - l_discount) as volume	from	supplier, lineitem, orders, 	customer, nation n1,	nation n2 where s_suppkey = l_suppkey	and o_orderkey = l_orderkey and c_custkey = o_custkey		and s_nationkey = n1.n_nationkey and c_nationkey = n2.n_nationkey	and  c_acctbal<=10000 and l_extendedprice<=22560 ) as temp";
			//query = "explain analyze FPC(\"catalog_sales\")  (\"150.5\") select ca_zip, cs_sales_price from catalog_sales,customer,customer_address,date_dim where cs_bill_customer_sk = c_customer_sk and c_current_addr_sk = ca_address_sk and cs_sold_date_sk = d_date_sk and ca_gmt_offset <= -7.0   and d_year <= 1900  and cs_list_price <= 150.5";
			query = prop.getProperty("query");
			
			cardinalityPath = prop.getProperty("cardinalityPath");
			
			
			int from_clause_int_val = Integer.parseInt(prop.getProperty("FROM_CLAUSE"));
			
			if(from_clause_int_val == 1)
				FROM_CLAUSE = true;
			else
				FROM_CLAUSE = false;
			
			sel_distribution = Integer.parseInt(prop.getProperty("sel_distribution"));
			
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}


void getContourPointsLohitOld(double cost, double errorboundval) throws IOException
{
	//Assume 
	//1. there is a List named "final_points";
	//2. Make sure you emptied "final_points" before calling this function; 
	
	
	ArrayList<Integer> remainingDimList = new ArrayList<Integer>();
	for(int i=0;i<dimension;i++)
	{
		if(learntDim.contains(i)!=true)
		{
			remainingDimList.add(i);
		}
		
	}
	
	if(remainingDimList.size()==1 )
	{ 
		int last_dim=-1;
		int [] arr = new int[dimension];
		for(int i=0;i<dimension;i++)
		{
			if(learntDim.contains(i))
			{
				System.out.println("i="+i+", indexof(i)= "+learntDim.indexOf(i)+"\n");
				arr[i] = learntDimIndices.get(i);
			}
			else
				last_dim = i;
			
		}
		//Search the whole line and return all the points which fall into the contour
		for(int i=0;i< resolution;i++)
		{
			arr[last_dim] = i;
			double cur_val = cost_generic(arr);
			double targetval = cost;
			
			if(cur_val >= targetval - errorboundval && cur_val <= targetval + errorboundval)
			{
			
				 point_generic p = new point_generic(arr,getPlanNumber_generic(arr),cur_val, remainingDim);
				 all_contour_points.add(p);
			}
		}
		
		
		return;
	}
	
	Integer curDim = remainingDimList.get(0); //index of 0 or size-1 does not matter
	Integer cur_index = resolution -1;
	
	while(cur_index >= 0)
	{
		learntDim.add(curDim);
		learntDimIndices.put(curDim,cur_index);
		getContourPointsLohitOld(cost, err*cost);
		learntDim.remove(learntDim.indexOf(curDim));
		learntDimIndices.remove(curDim);
		cur_index = cur_index - 1;
	}
	
}
public  void checkValidityofWeights() {
	double areaPlans =0;
	double relativeAreaPlans =0;
	areaSpace = 0.0;

	planRelativeArea = new double[totalPlans];

	for (int i=0; i< data.length; i++){
		areaSpace += locationWeight[i];
	}
	//	System.out.println(areaSpace);

	for (int i=0; i< totalPlans; i++){
		planRelativeArea[i] = planCount[i]/areaSpace;
		relativeAreaPlans += planRelativeArea[i];
		areaPlans += planCount[i];
	}
	//	System.out.println(areaPlans);
	//	System.out.println(relativeAreaPlans);

	if(relativeAreaPlans < 0.99) {
		System.out.println("ALERT! The area of plans add up to only " + relativeAreaPlans);
		//System.exit(0);
	}
}



public  void getPlanCountArray() {
	planCount = new double[totalPlans];
	locationWeight = new float[data.length];
	// Set dim depending on whether we are dealing with full packet or slice
	// int dim;


	int resln = resolution;
	int dim = dimension;

	int[] r = new int[dim];

	for (int i = 0; i < dim; i++)
		r[i] = resln;

	/*
	 * if(gdp.getDimension()==1) dim = 1; else if(data.length > r[0] * r[1])
	 * //full packet { dim = dim; //set to actual number of dimensions }
	 * else //slice { if(getDimension()==1) dim = 1; else dim = 2; //if
	 * actual dimension >= 2 }
	 */


		double locationWeightLocal[] = new double[resln];

			
			//for tpch
			locationWeightLocal[0] = 1;			locationWeightLocal[1] = 1;				locationWeightLocal[2] = 1;
			locationWeightLocal[3] = 2;         locationWeightLocal[4] = 4;				locationWeightLocal[5] = 7;
			locationWeightLocal[6] = 15;        locationWeightLocal[7] = 20;				locationWeightLocal[8] = 30;
			locationWeightLocal[9] = 20;
			
			//for tpcds
			locationWeightLocal[0] = 1;			locationWeightLocal[1] = 1;				locationWeightLocal[2] = 1;
			locationWeightLocal[3] = 2;         locationWeightLocal[4] = 4;				locationWeightLocal[5] = 6;
			locationWeightLocal[6] = 7;        locationWeightLocal[7] = 25;				locationWeightLocal[8] = 30;
			locationWeightLocal[9] = 20;



		for (int loc=0; loc < data.length; loc++)
		{
			double weight = 1.0;
			int tempLoc = loc;
			for(int d=0;d<dim;d++){
				weight *= locationWeightLocal[tempLoc % resln];
				tempLoc = tempLoc/10;
			}

			locationWeight[loc] = (float) weight;
			planCount[data[loc].getPlanNumber()] += weight;
		}



	for (int i = 0; i < data.length; i++) {
		if (planCount[data[i].getPlanNumber()] == 0)
			planCount[data[i].getPlanNumber()] = 1;
	}

	/*
	 * if(scaleupflag) { for(int i = 0; i < planCount.length; i++)
	 * planCount[i] /= 100Math.pow(10, getDimension()); }
	 */


	checkValidityofWeights();

}

// functions for location weight calculations and the checking for validity

//////////////////////////////   LOCATION WEIGHT CALCULATIONS and PCM & POSP VALIDITY CHECKING CODE  //////////////////////////////////////////////////////////////

public double maxHarmCalculation(int loc,double SO){
	int bestPlan  = getPCSTOptimalPlan(loc);
	double bestCost = AllPlanCosts[bestPlan][loc];
	int worstPlan = getPCSTWorstPlan(loc);
	double worstCost = AllPlanCosts[worstPlan][loc];
	double worst_native = (worstCost/bestCost);
	if(worst_native<1)
		System.out.println("MaxharmCalculation : error ");
	return (SO/worst_native - 1);
	//assert that this ratio is > 1
}

/*
 * for each location get the cheapest plan using the pcst files. 
 * This may be different from the optimizers choice due to imperfect
 * FPC implementation
 */
public int getPCSTOptimalPlan(int loc) {
	
	double bestCost = Double.MAX_VALUE;
	int opt = -1;
	for(int p=0; p<nPlans; p++){
		if(bestCost > AllPlanCosts[p][loc]) {
			bestCost = AllPlanCosts[p][loc];
			opt = p;
		}
	}
	return opt;
}

public int getPCSTWorstPlan(int loc) {
	
	double worstCost = Double.MIN_VALUE;
	int opt = -1;
	for(int p=0; p<nPlans; p++){
		if(worstCost < AllPlanCosts[p][loc]) {
			worstCost = AllPlanCosts[p][loc];
			opt = p;
		}
	}
	return opt;
}


 
}



class point_generic
{
	int dimension;
	
	ArrayList<Integer> order;
	ArrayList<Integer> storedOrder;
	int value;
	int p_no;
	double cost;
	static String plansPath;
	
	int [] dim_values;
	point_generic(int arr[], int num, double cost,ArrayList<Integer> remainingDim) throws  IOException{
		
		loadPropertiesFile();
		System.out.println();
		dim_values = new int[dimension];
		for(int i=0;i<dimension;i++){
			dim_values[i] = arr[i];
			System.out.print(arr[i]+",");
		}
		System.out.println("   having cost = "+cost+" and plan "+num);
		this.p_no = num;
		this.cost = cost;
		
		order =  new ArrayList<Integer>();
		storedOrder = new ArrayList<Integer>();
		FileReader file = new FileReader(plansPath+num+".txt");
	    
	    BufferedReader br = new BufferedReader(file);
	    String s;
	    while((s = br.readLine()) != null) {
	    	//System.out.println(Integer.parseInt(s));
	    	value = Integer.parseInt(s);
	    		storedOrder.add(value);

	    }
	    br.close();
	    file.close();
		
		
	}
	int getLearningDimension(){
		if(order.isEmpty())
			System.out.println("ERROR: all dimensions learnt");
		return order.get(0);
	}
	
	/*
	 * get the selectivity/index of the dimension
	 */
	public int get_dimension(int d){
		return dim_values[d];
	}
	
	/*
	 * get the plan number for this point
	 */
	public int get_plan_no(){
		return p_no;
	}
	
	public double get_cost(){
		return cost;
	}
	public int[] get_point_Index(){
		return dim_values;
	}
	public void remove_dimension(int d){
		String funName = "remove_dimension";
		if(order.isEmpty())
			System.out.println(funName+": ERROR: all dimensions learnt");
		if(order.contains(d))
			order.remove(order.indexOf(d));
		else 
			System.out.println(funName+": ERROR: removing a dimension that does not exist");

	}
	public void reloadOrderList(ArrayList<Integer> remainingDim) {
		
		order.clear();
		for(int itr=0;itr<storedOrder.size();itr++){
			if(remainingDim.contains(storedOrder.get(itr)))
				order.add(storedOrder.get(itr));
		}
	}
	public int get_no_of_dimension(){
		return dimension;
	}
	public void loadPropertiesFile() {

		Properties prop = new Properties();
		InputStream input = null;
	 
		try {
	 
			input = new FileInputStream("./src/Constants.properties");
	 
			// load a properties file
			prop.load(input);
	 
			// get the property value and print it out
			plansPath = prop.getProperty("apktPath");
			plansPath = plansPath+"predicateOrder/";
			dimension = Integer.parseInt(prop.getProperty("dimension"));
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}

}	