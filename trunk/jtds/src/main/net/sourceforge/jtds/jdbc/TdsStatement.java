//
// Copyright 1998 CDS Networks, Inc., Medford Oregon
//
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
// 1. Redistributions of source code must retain the above copyright
//    notice, this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright
//    notice, this list of conditions and the following disclaimer in the
//    documentation and/or other materials provided with the distribution.
// 3. All advertising materials mentioning features or use of this software
//    must display the following acknowledgement:
//      This product includes software developed by CDS Networks, Inc.
// 4. The name of CDS Networks, Inc.  may not be used to endorse or promote
//    products derived from this software without specific prior
//    written permission.
//
// THIS SOFTWARE IS PROVIDED BY CDS NETWORKS, INC. ``AS IS'' AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED.  IN NO EVENT SHALL CDS NETWORKS, INC. BE LIABLE
// FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
// DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
// OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
// HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
// OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
// SUCH DAMAGE.
//



/**
 * A Statement object is used for executing a static SQL statement and
 * obtaining the results produced by it.
 *
 * <p>Only one ResultSet per Statement can be open at any point in time.
 * Therefore, if the reading of one ResultSet is interleaved with the
 * reading of another, each must have been generated by different
 * Statements.  All statement execute methods implicitly close a
 * statement's current ResultSet if an open one exists.
 *
 * @see java.sql.Statement
 * @see ResultSet
 * @version $Id: TdsStatement.java,v 1.13 2004-02-05 23:57:55 alin_sinpalean Exp $
 */
package net.sourceforge.jtds.jdbc;

import java.sql.*;


public class TdsStatement implements java.sql.Statement
{
    public static final String cvsVersion = "$Id: TdsStatement.java,v 1.13 2004-02-05 23:57:55 alin_sinpalean Exp $";

    private TdsConnection connection; // The connection that created us

    SQLWarningChain warningChain = new SQLWarningChain(); // The warning chain
    TdsResultSet results = null;

    private Tds actTds = null;
    private boolean escapeProcessing = true;

    private int updateCount  = -1;
    private int maxFieldSize = (1<<31)-1;
    private int maxRows      = 0;
    private int timeout      = 0; // The timeout for a query
    private int fetchSize    = AbstractResultSet.DEFAULT_FETCH_SIZE;
    private int fetchDir     = ResultSet.FETCH_FORWARD;

    private int type = ResultSet.TYPE_FORWARD_ONLY;
    private int concurrency = ResultSet.CONCUR_READ_ONLY;

    private boolean isClosed = false;

    public OutputParamHandler outParamHandler;

    public TdsStatement(TdsConnection con, int type, int concurrency)
        throws SQLException
    {
        this.connection = con;
        this.type = type;
        this.concurrency = concurrency;
    }

    /**
     * Constructor for a Statement.  It simply sets the connection
     * that created us.
     *
     * @param  con  the Connection instance that creates us
     */
    public TdsStatement(TdsConnection con)
        throws SQLException
    {
        this(con, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    /**
     * Releases <code>actTds</code> IF there are no outstanding results.
     */
    protected synchronized void releaseTds() throws SQLException
    {
        // MJH remove test of autoCommit
        if( actTds == null )
            return;

        // Don't free the Tds if there are any results left.
        /** @todo Check if this is correct in case an IOException occurs */
        if( actTds.moreResults() )
            return;

        try
        {
            connection.freeTds(actTds);
            actTds = null;
        }
        catch (TdsException e)
        {
            throw new SQLException("Confusion in freeing Tds: " + e);
        }
    }

    protected void NotImplemented() throws java.sql.SQLException
    {
        throw new SQLException("Not Implemented");
    }

    /**
     * Execute an SQL statement that returns a single <code>ResultSet</code>.
     *
     * @param sql typically a static SQL SELECT statement
     * @return    a <code>ResultSet</code> that contains the data produced by
     *            the query; never <code>null</code>
     * @exception SQLException if a database access error occurs
     */
    public ResultSet executeQuery(String sql) throws SQLException
    {
        checkClosed();

        if( type == ResultSet.TYPE_FORWARD_ONLY &&
            concurrency == ResultSet.CONCUR_READ_ONLY )
        {
            if( internalExecute(sql) )
                return results;
            else
                throw new SQLException("No ResultSet was produced.");
        }
        else
            return new CursorResultSet(this, sql, fetchDir);
    }

    /**
     * This is the internal function that all subclasses should call.
     * It is not executeQuery() to allow subclasses (in particular
     * CursorResultSet) to override that functionality without
     * breaking the internal methods.
     *
     * @param sql any SQL statement
     * @return true if the next result is a ResulSet, false if it is
     *      an update count or there are no more results
     * @exception SQLException if a database access error occurs
     */
    public final synchronized boolean internalExecute(String sql) throws SQLException
    {
        checkClosed();
        return executeImpl(getTds(), sql, warningChain);
    }

    public final synchronized boolean internalExecute(String sql, Tds tds, SQLWarningChain wChain) throws SQLException
    {
        checkClosed();
        return executeImpl(tds, sql, wChain);
    }

    private final boolean executeImpl(Tds tds, String sql, SQLWarningChain wChain)
        throws SQLException {
        // Clear warnings, otherwise the last exception will be thrown.
        wChain.clearWarnings();
        updateCount = -1;

        // Consume all outstanding results. Otherwise it will either deadlock,
        // crash or return results from the previous query.
        skipToEnd();

        if (escapeProcessing) {
            sql = EscapeProcessor.nativeSQL(sql);
        }

        tds.executeQuery(sql, this, wChain, timeout);

        // SAfe We must do this to ensure we throw SQLExceptions on timed out
        //      statements
        wChain.checkForExceptions();

        return getMoreResults(tds, wChain, true);
    }

    public final synchronized boolean internalExecuteCall(String name, ParameterListItem[] formalParameterList,
        ParameterListItem[] actualParameterList, Tds tds, SQLWarningChain wChain) throws SQLException
    {
        checkClosed();
        return executeCallImpl(tds, name, formalParameterList, actualParameterList, wChain);
    }

    private boolean executeCallImpl(Tds tds, String name, ParameterListItem[] formalParameterList,
        ParameterListItem[] actualParameterList, SQLWarningChain wChain) throws SQLException {
        wChain.clearWarnings();
        // SAfe This is where all outstanding results must be skipped, to make
        //      sure they don't interfere with the the current ones.
        skipToEnd();

        // execute the stored procedure.
        tds.executeProcedure(name, formalParameterList, actualParameterList,
                             this, wChain, getQueryTimeout(), false);
        return getMoreResults(tds, wChain, true);
    }

    /**
     * Execute a SQL INSERT, UPDATE or DELETE statement.  In addition
     * SQL statements that return nothing such as SQL DDL statements
     * can be executed
     *
     * Any IDs generated for AUTO_INCREMENT fields can be retrieved
     * by looking through the SQLWarning chain of this statement
     * for warnings of the form "LAST_INSERTED_ID = 'some number',
     * COMMAND = 'your sql'".
     *
     * @param  sql  an SQL statement
     * @return      either a row count, or 0 for SQL commands
     * @exception SQLException if a database access error occurs
     */

    public synchronized int executeUpdate(String sql) throws SQLException {
        checkClosed();

        if (internalExecute(sql)) {
            skipToEnd();
            releaseTds();
            throw new SQLException("executeUpdate can't return a result set");
        } else {
            int res;
            while (((res = getUpdateCount()) != -1)
                    && connection.returnLastUpdateCount()) {

                // If we found a ResultSet, there's a problem.
                if( getMoreResults() ) {
                    skipToEnd();
                    releaseTds();
                    throw new SQLException("executeUpdate can't return a result set");
                }
            }

            releaseTds();
            // We should return 0 (at least that's what the javadoc above says)
            return res==-1 ? 0 : res;
        }
    }

    protected synchronized void closeResults(boolean allowTdsRelease)
        throws java.sql.SQLException
    {
        updateCount = -1;

        if( results != null )
        {
            results.close(allowTdsRelease);
            results = null;
        }
    }

    /**
     * Eats all available input from the server. Not very efficient (since it
     * reads in all data by creating <code>ResultSets</code> and processing
     * them), but at least it works (the old version would crash when reading in
     * a row because it didn't have any information about the row's Context).
     * <p>
     * This could be changed to use the <code>TdsComm</code> to read in all the
     * server response without processing it, but that requires some changes in
     * <code>TdsComm</code>, too.
     */
    protected synchronized void skipToEnd() throws java.sql.SQLException
    {
        closeResults(false);

        if( actTds != null )
        {
            actTds.skipToEnd();
//            releaseTds();

            // SAfe This is the only place we should send a CANCEL packet
            //      ourselves. We can't do that in Tds.discardResultSet because
            //      we could cancel other results when what we want is to only
            //      close the ResultSet in order to get the other results.

            // SAfe On a second thought, we'd better not cancel the execution.
            //      Someone could run a big update script and not process all
            //      the results, thinking that as long as there were no
            //      exceptions, all went right, but we would cancel the script.
            //      Anyway, this was a good test for the cancel mechanism. :o)
//            try
//            {
//                if( actTds.moreResults() )
//                    actTds.cancel();
//            }
//            catch( java.io.IOException ex )
//            {
//                throw new SQLException(ex.toString());
//            }
//            catch( TdsException ex )
//            {
//                throw new SQLException(ex.toString());
//            }

            // SAfe And now, keep consuming data and hope the server cancels
            //      this as soon as possible (I'm not so sure, however, that
            //      this will happen; I think that even if the data amount is
            //      huge, the server sends it as soon as possible so the cancel
            //      could come too late; or not?)
//            while( getMoreResults(actTds, warningChain, false) || updateCount!=-1 );
        }
    }

    /**
     * In many cases, it is desirable to immediately release a
     * Statement's database and JDBC resources instead of waiting
     * for this to happen when it is automatically closed.  The
     * close method provides this immediate release.
     *
     * <p><B>Note:</B> A Statement is automatically closed when it is
     * garbage collected.  When a Statement is closed, its current
     * ResultSet, if one exists, is also closed.
     *
     * @exception SQLException if a database access error occurs (why?)
     */
    public synchronized void close() throws SQLException
    {
        if( isClosed )
            return;

        // SAfe Mark Statement as closed internally, too
        isClosed = true;

        if( actTds != null )
            // Tds not yet released.
            try
            {
                // SAfe: Must do this to ensure no garbage is left behind
                closeResults(false);

                if( !connection.isClosed() )
                    actTds.skipToEnd();

                // MJH Do not Rollback any pending transactions!
                connection.freeTds(actTds);
                actTds = null;
            }
            catch( net.sourceforge.jtds.jdbc.TdsException e )
            {
                throw new SQLException(e.toString());
            }
    }

    /**
     * Make sure we release the <code>Tds</code> when we're no longer in use.
     * This is safe to do even in the case of <code>DatabaseMetaData</code> or
     * other cases where the reference to the <code>Statement</code> is lost
     * and only a reference to a <code>ResultSet</code> is kept, because the
     * <code>TdsResultSet</code> has an internal reference to the
     * <code>Statement</code> so <code>finalize()</code> won't get called yet.
     * @throws SQLException
     */
    public void finalize() throws SQLException
    {
        close();
    }

    /**
     * The maxFieldSize limit (in bytes) is the maximum amount of
     * data returned for any column value; it only applies to
     * BINARY, VARBINARY, LONGVARBINARY, CHAR, VARCHAR and LONGVARCHAR
     * columns.  If the limit is exceeded, the excess data is silently
     * discarded.
     *
     * @return the current max column size limit; zero means unlimited
     * @exception SQLException if a database access error occurs
     */
    public synchronized int getMaxFieldSize() throws SQLException
    {
        checkClosed();
        return maxFieldSize;
    }

    /**
     * Sets the maxFieldSize
     *
     * @param max the new max column size limit; zero means unlimited
     * @exception SQLException if size exceeds buffer size
     */
    public synchronized void setMaxFieldSize(int max) throws SQLException
    {
        checkClosed();
        maxFieldSize = max;
    }

    /**
     * The maxRows limit is set to limit the number of rows that
     * any ResultSet can contain.  If the limit is exceeded, the
     * excess rows are silently dropped.
     *
     * @return the current maximum row limit; zero means unlimited
     * @exception SQLException if a database access error occurs
     */
    public synchronized int getMaxRows() throws SQLException
    {
        checkClosed();
        return maxRows;
    }

    /**
     * Set the maximum number of rows
     *
     * @param max the new max rows limit; zero means unlimited
     * @exception SQLException if a database access error occurs
     * @see #getMaxRows
     */
    public synchronized void setMaxRows(int max) throws SQLException
    {
        checkClosed();

        if( maxRows < 0 )
            throw new SQLException("Negative row count");
        maxRows = max;
    }

    /**
     * If escape scanning is on (the default), the driver will do escape
     * substitution before sending the SQL to the database.
     *
     * @param enable true to enable; false to disable
     * @exception SQLException if a database access error occurs
     */
    public synchronized void setEscapeProcessing(boolean enable) throws SQLException
    {
        checkClosed();
        escapeProcessing = enable;
    }

    /**
     * The queryTimeout limit is the number of seconds the driver
     * will wait for a Statement to execute.  If the limit is
     * exceeded, a SQLException is thrown.
     *
     * @return the current query timeout limit in seconds; 0 = unlimited
     * @exception SQLException if a database access error occurs
     */
    public synchronized int getQueryTimeout() throws SQLException
    {
        checkClosed();
        return timeout;
    }

    /**
     * Sets the queryTimeout limit
     *
     * @param seconds - the new query timeout limit in seconds
     * @exception SQLException if a database access error occurs
     */
    public synchronized void setQueryTimeout(int seconds) throws SQLException
    {
        checkClosed();
        timeout = seconds;
    }

   /**
    *
    * @exception SQLException
    */
    public void cancel() throws SQLException
    {
        checkClosed();

        try
        {
            if( actTds != null )
                actTds.cancel();
        }
        catch(net.sourceforge.jtds.jdbc.TdsException e)
        {
            throw new SQLException(e.getMessage());
        }
        catch(java.io.IOException e)
        {
            throw new SQLException(e.getMessage());
        }
    }

    /**
     * The first warning reported by calls on this Statement is
     * returned.  A Statement's execute methods clear its SQLWarning
     * chain.  Subsequent Statement warnings will be chained to this
     * SQLWarning.
     *
     * <p>The Warning chain is automatically cleared each time a statement
     * is (re)executed.
     *
     * <p><B>Note:</B>  If you are processing a ResultSet then any warnings
     * associated with ResultSet reads will be chained on the ResultSet
     * object.
     *
     * @return the first SQLWarning on null
     * @exception SQLException if a database access error occurs
     */
    public synchronized SQLWarning getWarnings() throws SQLException
    {
        checkClosed();
        return warningChain.getWarnings();
    }


   /**
    * After this call, getWarnings returns null until a new warning
    * is reported for this Statement.
    *
    * @exception SQLException if a database access error occurs (why?)
    */
    public synchronized void clearWarnings() throws SQLException
    {
        checkClosed();
        warningChain.clearWarnings();
    }

    /**
     * setCursorName defines the SQL cursor name that will be used by
     * subsequent execute methods.  This name can then be used in SQL
     * positioned update/delete statements to identify the current row
     * in the ResultSet generated by this statement.  If a database
     * doesn't support positioned update/delete, this method is a
     * no-op.
     *
     * @param name the new cursor name
     * @exception SQLException if a database access error occurs
     */
    public void setCursorName(String name) throws SQLException
    {
        // SAfe As the javadoc above says, this should be a no-op.
    }

    public synchronized boolean execute(String sql) throws SQLException
    {
        checkClosed();
        return internalExecute(sql);
    }

    synchronized Tds getTds() throws SQLException
    {
        if( actTds == null )
        {
            actTds=connection.allocateTds(false);
            return actTds;
        }
        else
            return actTds;
    }

    /**
     * getResultSet returns the current result as a ResultSet.  It
     * should only be called once per result.
     *
     * @return the current result set; null if there are no more
     * @exception SQLException if a database access error occurs
     */
    public synchronized java.sql.ResultSet getResultSet() throws SQLException
    {
        checkClosed();
        return results;
    }

    /**
     * getUpdateCount returns the current result as an update count,
     * if the result is a ResultSet or there are no more results, -1
     * is returned.  It should only be called once per result.
     *
     * @return the current result as an update count.
     * @exception SQLException if a database access error occurs
     */
    public synchronized int getUpdateCount() throws SQLException
    {
        checkClosed();
        return updateCount;
    }

    /**
     * getMoreResults moves to a Statement's next result.  If it returns
     * true, this result is a ResulSet.
     *
     * @return true if the next ResultSet is valid
     * @exception SQLException if a database access error occurs
     */
    public synchronized boolean getMoreResults() throws SQLException
    {
        checkClosed();
        return getMoreResults(actTds, warningChain, true);
    }

    public boolean getMoreResults(int current) throws SQLException {
        NotImplemented();
        return false;
    }

    boolean handleRetStat(PacketRetStatResult packet)
    {
        return outParamHandler!=null && outParamHandler.handleRetStat(packet);
    }

    boolean handleParamResult(PacketOutputParamResult packet)
        throws SQLException
    {
        return outParamHandler!=null && outParamHandler.handleParamResult(packet);
    }

    synchronized boolean getMoreResults(Tds tds, SQLWarningChain wChain, boolean allowTdsRelease)
        throws SQLException
    {
        updateCount = -1;

        if( tds == null )
            return false;

        // Reset all internal variables (do it before checking for more results)
        closeResults(false);

        // SAfe Synchronize on the Tds to make sure noone else can call
        //      Tds.skipToEnd while we're processing the results
        synchronized( tds )
        {
            try
            {
                tds.goToNextResult(wChain, this);

                if( !tds.moreResults() )
                {
                    if( allowTdsRelease )
                        releaseTds();
                    return false;
                }

                // SAfe We found a ResultSet
                if( tds.isResultSet() )
                {
                    results = new TdsResultSet(tds, this, wChain, fetchSize);
                    return true;
                }

                // SAfe It's a row count. Only TDS_DONE for Statements and
                //      TDS_DONEINPROC for PreparedStatements are row counts
                PacketEndTokenResult end =
                    (PacketEndTokenResult)tds.processSubPacket();
                updateCount = end.getRowCount();

                // SAfe Eat up all packets until the next result or the end
                tds.goToNextResult(wChain, this);

                if( allowTdsRelease )
                    releaseTds();

                wChain.checkForExceptions();

                return false;
            }
            catch( Exception ex )
            {
                releaseTds();
                if( ex instanceof SQLException )
                    throw (SQLException)ex;
                else
                    throw new SQLException("Network error: " + ex.getMessage());
            }
        }
    }

    //--------------------------JDBC 2.0-----------------------------

    /**
     * JDBC 2.0
     *
     * Gives the driver a hint as to the direction in which
     * the rows in a result set
     * will be processed. The hint applies only to result sets created
     * using this Statement object.  The default value is
     * ResultSet.FETCH_FORWARD.
     * <p>Note that this method sets the default fetch direction for
     * result sets generated by this <code>Statement</code> object.
     * Each result set has its own methods for getting and setting
     * its own fetch direction.
     * @param direction the initial direction for processing rows
     * @exception SQLException if a database access error occurs
     * or the given direction
     * is not one of ResultSet.FETCH_FORWARD, ResultSet.FETCH_REVERSE, or
     * ResultSet.FETCH_UNKNOWN
     */
    public void setFetchDirection(int direction) throws SQLException
    {
        if( direction!=ResultSet.FETCH_FORWARD && direction!=ResultSet.FETCH_REVERSE && direction!=ResultSet.FETCH_UNKNOWN )
            throw new SQLException("Invalid fetch direction.");
        fetchDir = direction;
    }

    /**
     * JDBC 2.0
     *
     * Retrieves the direction for fetching rows from
     * database tables that is the default for result sets
     * generated from this <code>Statement</code> object.
     * If this <code>Statement</code> object has not set
     * a fetch direction by calling the method <code>setFetchDirection</code>,
     * the return value is implementation-specific.
     *
     * @return the default fetch direction for result sets generated
     *          from this <code>Statement</code> object
     * @exception SQLException if a database access error occurs
     */
    public int getFetchDirection() throws SQLException
    {
        return fetchDir;
    }

    /**
     * JDBC 2.0
     *
     * Gives the JDBC driver a hint as to the number of rows that should
     * be fetched from the database when more rows are needed.  The number
     * of rows specified affects only result sets created using this
     * statement. If the value specified is zero, then the hint is ignored.
     * The default value is zero.
     *
     * @param rows the number of rows to fetch
     * @exception SQLException if a database access error occurs, or the
     * condition 0 <= rows <= this.getMaxRows() is not satisfied.
     */
    public void setFetchSize(int rows) throws SQLException
    {
        if( rows < 0 )
            throw new SQLException("Invalid fetch size.");
        fetchSize = rows;
    }

    /**
     * JDBC 2.0
     *
     * Retrieves the number of result set rows that is the default
     * fetch size for result sets
     * generated from this <code>Statement</code> object.
     * If this <code>Statement</code> object has not set
     * a fetch size by calling the method <code>setFetchSize</code>,
     * the return value is implementation-specific.
     * @return the default fetch size for result sets generated
     *          from this <code>Statement</code> object
     * @exception SQLException if a database access error occurs
     */
    public int getFetchSize() throws SQLException
    {
        return fetchSize;
    }

    /**
     * JDBC 2.0
     *
     * Retrieves the result set concurrency.
     * <p>
     * <b>Note:</b> No need for synchronization. Value never changes.
     */
    public int getResultSetConcurrency() throws SQLException
    {
        checkClosed();
        return concurrency;
    }

    /**
     * JDBC 2.0
     *
     * Determine the result set type.
     * <p>
     * <b>Note:</b> No need for synchronization. Value never changes.
     */
    public int getResultSetType()  throws SQLException
    {
        checkClosed();
        return type;
    }

    /**
     * JDBC 2.0
     *
     * Adds a SQL command to the current batch of commmands for the statement.
     * This method is optional.
     *
     * @param sql typically this is a static SQL INSERT or UPDATE statement
     * @exception SQLException if a database access error occurs, or the
     * driver does not support batch statements
     */
    public void addBatch( String sql ) throws SQLException
    {
        NotImplemented();
    }

    /**
     * JDBC 2.0
     *
     * Makes the set of commands in the current batch empty.
     * This method is optional.
     *
     * @exception SQLException if a database access error occurs or the
     * driver does not support batch statements
     */
    public void clearBatch() throws SQLException
    {
        NotImplemented();
    }

    /**
     * JDBC 2.0
     *
     * Submits a batch of commands to the database for execution.
     * This method is optional.
     *
     * @return an array of update counts containing one element for each
     * command in the batch.  The array is ordered according
     * to the order in which commands were inserted into the batch.
     * @exception SQLException if a database access error occurs or the
     * driver does not support batch statements
     */
    public int[] executeBatch() throws SQLException
    {
        NotImplemented();
        return null;
    }

    /**
     * JDBC 2.0
     *
     * Returns the <code>Connection</code> object
     * that produced this <code>Statement</code> object.
     * <p>
     * <b>Note:</b> No need for synchromization here. <code>connection</code>
     * doesn't change during execution (not even after <code>close()</code>.
     *
     * @return the connection that produced this statement
     * @exception SQLException if a database access error occurs
     */
    public java.sql.Connection getConnection() throws SQLException
    {
        checkClosed();
        return connection;
    }

    private void checkClosed() throws SQLException
    {
        if( isClosed || connection.isClosed() )
            throw new SQLException("Statement already closed.");
    }

    public boolean execute(String str, int param) throws java.sql.SQLException {
        NotImplemented();
        return false;
    }

    public boolean execute(String str, String[] str1) throws java.sql.SQLException {
        NotImplemented();
        return false;
    }

    public boolean execute(String str, int[] values) throws java.sql.SQLException {
        NotImplemented();
        return false;
    }

    public int executeUpdate(String str, String[] str1) throws java.sql.SQLException {
        NotImplemented();
        return Integer.MIN_VALUE;
    }

    public int executeUpdate(String str, int[] values) throws java.sql.SQLException {
        NotImplemented();
        return Integer.MIN_VALUE;
    }

    public int executeUpdate(String str, int param) throws java.sql.SQLException {
        NotImplemented();
        return Integer.MIN_VALUE;
    }

    public java.sql.ResultSet getGeneratedKeys() throws java.sql.SQLException {
        NotImplemented();
        return null;
    }

    public int getResultSetHoldability() throws java.sql.SQLException {
        NotImplemented();
        return Integer.MIN_VALUE;
    }
}
