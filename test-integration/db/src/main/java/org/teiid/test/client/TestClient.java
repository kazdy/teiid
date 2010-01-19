/*
 * JBoss, Home of Professional Open Source.
 * See the COPYRIGHT.txt file distributed with this work for information
 * regarding copyright ownership.  Some portions may be licensed
 * to Red Hat, Inc. under one or more contributor license agreements.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA.
 */

package org.teiid.test.client;

import java.text.SimpleDateFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.junit.Assert;
import org.teiid.test.framework.ConfigPropertyLoader;
import org.teiid.test.framework.ConfigPropertyNames;
import org.teiid.test.framework.TestLogger;
import org.teiid.test.framework.TransactionContainer;
import org.teiid.test.framework.connection.DataSourceConnection;
import org.teiid.test.framework.connection.DriverConnection;
import org.teiid.test.framework.exception.QueryTestFailedException;
import org.teiid.test.framework.exception.TransactionRuntimeException;

import com.metamatrix.common.util.PropertiesUtils;
import com.metamatrix.core.util.FileUtils;
import com.metamatrix.core.util.StringUtil;


/**
 * TestClient is the starter class for running bulk sql testing against a Teiid server.
 * The bulk testing is about testing a lot of queries against a predefined set of 
 * expected results and providing error files when comparisons don't match.  
 * The process 
 * The bulk testing, in its simplicity, will do the following:
 * <li>use a {@link QueryReader} to read the queries that it will execute</li>
 * <li>based on the results of each query executed, the process will compare the results
 * to the {@link ExpectedResults }.</li>
 * <li>If the {@link TestProperties#PROP_RESULT_MODE} option is set to {@link TestProperties.RESULT_MODES#GENERATE}
 * 	then the process will not perform a comparison, but generate a new set of expected result files that
 * 	can in turn be used as the
 * @author vanhalbert
 *
 */
public class TestClient  {

    public static final SimpleDateFormat TSFORMAT = new SimpleDateFormat(
	    "HH:mm:ss.SSS"); //$NON-NLS-1$
    
    
    private Properties overrides = new Properties();
    
    static {
	if (System.getProperty(ConfigPropertyNames.CONFIG_FILE ) == null) {
		System.setProperty(ConfigPropertyNames.CONFIG_FILE,"./ctc_tests/ctc-test.properties");
	} else {
	    System.out.println("Config File Set: " + System.getProperty(ConfigPropertyNames.CONFIG_FILE ));
	}
	
	// the project.loc is used 
	if (System.getProperty("project.loc" ) == null) {
		System.setProperty("project.loc",".");
	}

    }

    public TestClient() {


    }
    
    public static void main(String[] args) {

	TestClient tc = new TestClient();
	tc.runTest();
	
	
    }
    
    public void runTest() {
	
	try {
    
	    runScenario();
	    
	} catch (Throwable t) {
	    t.printStackTrace();
	}
	
    }
 
    
    private void runScenario() throws Exception {
	
	
	String scenario_file = ConfigPropertyLoader.getInstance().getProperty(TestProperties.PROP_SCENARIO_FILE);
	if (scenario_file == null) {
	    throw new TransactionRuntimeException(TestProperties.PROP_SCENARIO_FILE + " property was not defined");
	}

	String scenario_name = FileUtils.getBaseFileNameWithoutExtension(scenario_file);
	
	TestLogger.log("Starting scenario " + scenario_name);
	
	Properties sc_props = PropertiesUtils.load(scenario_file);
	
	// 1st perform substitution on the scenario file based on the config and system properties file 
	// because the next substitution is based on the scenario file
	Properties sc_updates = getSubstitutedProperties(sc_props);
	if (!sc_updates.isEmpty()) {
	    sc_props.putAll(sc_updates);
	    this.overrides.putAll(sc_props);
	    
	}
	ConfigPropertyLoader.getInstance().setProperties(sc_props);
	
	// 2nd perform substitution on current configuration - which will be based on the config properties file
	Properties config_updates = getSubstitutedProperties(ConfigPropertyLoader.getInstance().getProperties());
	if (!config_updates.isEmpty()) {
	    this.overrides.putAll(config_updates);
	    ConfigPropertyLoader.getInstance().setProperties(config_updates);
	}


	
	// update the URL with the vdb that is to be used
	String url = ConfigPropertyLoader.getInstance().getProperty(DriverConnection.DS_URL);
	String vdb_name = ConfigPropertyLoader.getInstance().getProperty(DataSourceConnection.DS_DATABASENAME);
	
	Assert.assertNotNull(DataSourceConnection.DS_DATABASENAME + " property not set, need it for the vdb name", vdb_name);
	url = StringUtil.replace(url, "${vdb}", vdb_name);
	
	ConfigPropertyLoader.getInstance().setProperty(DriverConnection.DS_URL, url);
	
	
	QueryScenario set = ClassFactory.createQueryScenario(scenario_name);
	
	TransactionContainer tc = getTransactionContainter();

	runTestCase(set,  tc);

	TestLogger.log("Completed scenario " + scenario_name);
    }
    
    private void runTestCase(QueryScenario queryset,  TransactionContainer tc) throws Exception {
	String querySetID = null;
	List<QueryTest> queryTests = null;
	
	TestClientTransaction userTxn = new TestClientTransaction(queryset);
	
	Iterator<String> qsetIt = queryset.getQuerySetIDs().iterator();
	
	TestResultsSummary summary = new TestResultsSummary();

	// iterate over the query set ID's, which there
	// should be 1 for each file to be processed
	while (qsetIt.hasNext()) {
	    querySetID = qsetIt.next();

	    TestLogger.logInfo("Start Test Query ID [" + querySetID + "]");

	    queryTests = queryset.getQueries(querySetID);

		 // the iterator to process the query tests
	    Iterator<QueryTest> queryTestIt = null;
	    queryTestIt = queryTests.iterator();
	    
	    ExpectedResults expectedResults = queryset.getExpectedResults(querySetID);

	    
	    
	    
	    long beginTS = System.currentTimeMillis();
	    long endTS = 0;
	    
        	while (queryTestIt.hasNext()) {
        	    QueryTest q = queryTestIt.next();
                     	    
            	    userTxn.init(summary, expectedResults, q);
             	    
        	    // run test
            	    tc.runTransaction(userTxn);
	             
        	}
        	
        	endTS = System.currentTimeMillis();
        	
        	TestLogger.logInfo("End Test Query ID [" + querySetID + "]");
        	
        	summary.printResults(queryset, querySetID,beginTS, endTS);        	
  
	}
	
	
	summary.printTotals(queryset);
	summary.cleanup();
	
	// cleanup all connections created for this test.
	userTxn.getConnectionStrategy().shutdown();
	ConfigPropertyLoader.reset();


        
	
    }

    
    protected TransactionContainer getTransactionContainter() {
	try {
	    return TransactionFactory.create(ConfigPropertyLoader.getInstance());
	} catch (QueryTestFailedException e) {
	    // TODO Auto-generated catch block
	    e.printStackTrace();
	    throw new TransactionRuntimeException(e);
	}

    }

    
    private Properties getSubstitutedProperties(Properties props) {
	Properties or = new Properties();
	
	Properties configprops = ConfigPropertyLoader.getInstance().getProperties();
	
	configprops.putAll(props);
	
	or = PropertiesUtils.resolveNestedProperties(configprops);
	
	return or;

    }

    


    /**
     * One-time synchronization barrier that allows dynamically reducing the
     * number of threads expected at the barrier. Captures the timestamp when
     * all the expected threads arrive at the barrier.
     * 
     * @since 4.3
     */
//    private static final class TimestampedSynchronizationBarrier {
//	private int expectedThreads;
//	private int currentThreads = 0;
//	private long leaveBarrierTimestamp = -1;
//
//	private TimestampedSynchronizationBarrier(int expectedThreads) {
//	    this.expectedThreads = expectedThreads;
//	}
//
//	private synchronized void barrier(final long waitTime) {
//	    if ((++currentThreads) == expectedThreads) {
//		// If all the expected threads have arrived at the barrier, then
//		// wake them all.
//		leaveBarrierTimestamp = System.currentTimeMillis();
//		this.notifyAll();
//	    } else {
//		// Otherwise, wait for other threads to arrive.
//		try {
//		    wait(waitTime);
//		} catch (InterruptedException e) {
//		    System.err
//			    .println("A thread was unexpectedly interrupted while waiting for other threads to enter the barrier. The measurements for this test will not be accurate.");
//		    e.printStackTrace(System.err);
//		    // Let the thread continue on its merry way
//		}
//	    }
//	}
//
//	private synchronized void decrementExpectedThreads() {
//	    if ((--expectedThreads) == currentThreads) {
//		// If all the remaining threads are already waiting, then wake
//		// them all.
//		leaveBarrierTimestamp = System.currentTimeMillis();
//		this.notifyAll();
//	    }
//	}
//    }

}
