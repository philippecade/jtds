//
// Copyright 1998, 1999 CDS Networks, Inc., Medford Oregon
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


package com.internetcds.jdbc.tds;

import java.sql.*;
import java.math.BigDecimal;
import java.util.Vector;
// import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Calendar;
import java.io.*;

/**
 *  <P>
 *
 *  A ResultSet provides access to a table of data generated by executing a
 *  Statement. The table rows are retrieved in sequence. Within a row its column
 *  values can be accessed in any order. <P>
 *
 *  A ResultSet maintains a cursor pointing to its current row of data.
 *  Initially the cursor is positioned before the first row. The 'next' method
 *  moves the cursor to the next row. <P>
 *
 *  The getXXX methods retrieve column values for the current row. You can
 *  retrieve values either using the index number of the column, or by using the
 *  name of the column. In general using the column index will be more
 *  efficient. Columns are numbered from 1. <P>
 *
 *  For maximum portability, ResultSet columns within each row should be read in
 *  left-to-right order and each column should be read only once. <P>
 *
 *  For the getXXX methods, the JDBC driver attempts to convert the underlying
 *  data to the specified Java type and returns a suitable Java value. See the
 *  JDBC specification for allowable mappings from SQL types to Java types with
 *  the ResultSet.getXXX methods. <P>
 *
 *  Column names used as input to getXXX methods are case insensitive. When
 *  performing a getXXX using a column name, if several columns have the same
 *  name, then the value of the first matching column will be returned. The
 *  column name option is designed to be used when column names are used in the
 *  SQL query. For columns that are NOT explicitly named in the query, it is
 *  best to use column numbers. If column names were used there is no way for
 *  the programmer to guarantee that they actually refer to the intended
 *  columns. <P>
 *
 *  A ResultSet is automatically closed by the Statement that generated it when
 *  that Statement is closed, re-executed, or is used to retrieve the next
 *  result from a sequence of multiple results. <P>
 *
 *  The number, types and properties of a ResultSet's columns are provided by
 *  the ResulSetMetaData object returned by the getMetaData method.
 *
 *@author     Craig Spannring
 *@author     Alin Sinpalean
 *@author     The FreeTDS project
 *@created    17 March 2001
 *@version    $Id: TdsResultSet.java,v 1.10 2002-09-09 12:14:32 alin_sinpalean Exp $
 *@see        Statement#executeQuery
 *@see        Statement#getResultSet
 *@see        ResultSetMetaData @
 *@see        Tds#getRow
 */

public class TdsResultSet extends AbstractResultSet implements ResultSet
{
    Tds tds = null;
    TdsStatement stmt = null;

    Context context = null;

    int row = 0;
    boolean hitEndOfData = false;
    boolean isClosed = false;

    int rowIndex = -1;
    int rowCount = 0;
    PacketRowResult[] rowCache = null;

    /**
     *  Description of the Field
     */
    public final static String cvsVersion = "$Id: TdsResultSet.java,v 1.10 2002-09-09 12:14:32 alin_sinpalean Exp $";

    public TdsResultSet(Tds tds_, TdsStatement stmt_, Columns columns)
    {
        tds = tds_;
        stmt = stmt_;
        context = new Context(columns, tds.getEncoder());

        hitEndOfData = false;
        warningChain = new SQLWarningChain();
        rowCache = new PacketRowResult[fetchSize];
    }

    public Context getContext()
    {
        return context;
    }

    /**
     *  JDBC 2.0 Gives a hint as to the direction in which the rows in this
     *  result set will be processed. The initial value is determined by the
     *  statement that produced the result set. The fetch direction may be
     *  changed at any time.
     *
     *@param  direction         The new FetchDirection value
     *@exception  SQLException  if a database access error occurs or the result
     *      set type is TYPE_FORWARD_ONLY and the fetch direction is not
     *      FETCH_FORWARD.
     */
    public void setFetchDirection(int direction) throws SQLException
    {
        if (getType() == TYPE_FORWARD_ONLY && direction != FETCH_FORWARD) {
            throw new SQLException(
                    "The result set type is TYPE_FORWARD_ONLY "
                     + "and the fetch direction is not FETCH_FORWARD");
        }
    }

    /**
     *  JDBC 2.0 Gives the JDBC driver a hint as to the number of rows that
     *  should be fetched from the database when more rows are needed for this
     *  result set. If the fetch size specified is zero, the JDBC driver ignores
     *  the value and is free to make its own best guess as to what the fetch
     *  size should be. The default value is set by the statement that created
     *  the result set. The fetch size may be changed at any time.
     *
     *@param  rows              the number of rows to fetch
     *@exception  SQLException  if a database access error occurs or the
     *      condition 0 <= rows <= this.getMaxRows() is not satisfied.
     */
    public synchronized void setFetchSize(int rows) throws SQLException
    {
        int maxRows = stmt.getMaxRows();

        if( rows<0 || (maxRows>0 && rows>maxRows) )
            throw new SQLException("Illegal fetch size: "+rows);

        // If the user lets us choose, we'll use the whole cache
        if( rows == 0 )
        {
            fetchSize = rowCache.length;
            return;
        }

        // Reallocate the cache if too small
        if( rows > rowCache.length )
        {
            PacketRowResult[] newCache = new PacketRowResult[rows];
            System.arraycopy(rowCache, 0, newCache, 0, rowCache.length);
            rowCache = newCache;
        }

        fetchSize = rows;
    }

    /**
     *  Get the name of the SQL cursor used by this ResultSet. <P>
     *
     *  In SQL, a result table is retrieved through a cursor that is named. The
     *  current row of a result can be updated or deleted using a positioned
     *  update/delete statement that references the cursor name. <P>
     *
     *  JDBC supports this SQL feature by providing the name of the SQL cursor
     *  used by a ResultSet. The current row of a ResultSet is also the current
     *  row of this SQL cursor. <P>
     *
     *  <B>Note:</B> If positioned update is not supported a SQLException is
     *  thrown
     *
     *@return                   the ResultSet's SQL cursor name
     *@exception  SQLException  if a database-access error occurs.
     */
    public String getCursorName() throws SQLException
    {
        throw new SQLException("Not implemented (getCursorName)");
    }

    public SQLWarning getWarnings() throws SQLException
    {
        return warningChain.getWarnings();
    }

    //---------------------------------------------------------------------
    // Traversal/Positioning
    //---------------------------------------------------------------------

    /**
     *  JDBC 2.0 <p>
     *
     *  Indicates whether the cursor is before the first row in the result set.
     *
     *@return                   true if the cursor is before the first row,
     *      false otherwise. Returns false when the result set contains no rows.
     *@exception  SQLException  if a database access error occurs
     */
    public synchronized boolean isBeforeFirst() throws SQLException
    {
        return row==0 && haveMoreResults();
    }

    /**
     *  JDBC 2.0 <p>
     *
     *  Indicates whether the cursor is after the last row in the result set.
     *
     *@return                   true if the cursor is after the last row, false
     *      otherwise. Returns false when the result set contains no rows.
     *@exception  SQLException  if a database access error occurs
     */
    public boolean isAfterLast() throws SQLException
    {
        return hitEndOfData;
    }

    /**
     *  JDBC 2.0 <p>
     *
     *  Indicates whether the cursor is on the first row of the result set.
     *
     *@return                   true if the cursor is on the first row, false
     *      otherwise.
     *@exception  SQLException  if a database access error occurs
     */
    public boolean isFirst() throws SQLException
    {
        return row == 1;
    }

    /**
     *  JDBC 2.0 <p>
     *
     *  Indicates whether the cursor is on the last row of the result set. Note:
     *  Calling the method <code>isLast</code> may be expensive because the JDBC
     *  driver might need to fetch ahead one row in order to determine whether
     *  the current row is the last row in the result set.
     *
     *@return                   true if the cursor is on the last row, false
     *      otherwise.
     *@exception  SQLException  if a database access error occurs
     */
    public boolean isLast() throws SQLException
    {
        /** @todo Implement isLast */
        throw new SQLException("Cannot determine position on a FORWARD_ONLY RecordSet.");
    }

    public int getRow() throws SQLException
    {
        return row;
    }

    public int getFetchDirection() throws SQLException
    {
        return ResultSet.FETCH_FORWARD;
    }

    public int getFetchSize() throws SQLException
    {
        return fetchSize;
    }

    public int getType() throws SQLException
    {
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    public int getConcurrency() throws SQLException
    {
        return ResultSet.CONCUR_READ_ONLY;
    }

    public java.sql.Statement getStatement() throws SQLException
    {
        return stmt;
    }

    public void clearWarnings() throws SQLException
    {
        warningChain.clearWarnings();
    }

    /**
     *  In some cases, it is desirable to immediately release a ResultSet's
     *  database and JDBC resources instead of waiting for this to happen when
     *  it is automatically closed; the close method provides this immediate
     *  release. <P>
     *
     *  <B>Note:</B> A ResultSet is automatically closed by the Statement that
     *  generated it when that Statement is closed, re-executed, or is used to
     *  retrieve the next result from a sequence of multiple results. A
     *  ResultSet is also automatically closed when it is garbage collected.
     *
     *@exception  SQLException  if a database-access error occurs.
     */
    public synchronized void close() throws SQLException
    {
        close(true);
    }

    public synchronized void close(boolean allowTdsRelease) throws SQLException
    {
        Exception exception = null;

        /** @todo SAfe: Maybe an exception should be thrown here */
        if( isClosed )
            return;
        isClosed = true;

        if( !hitEndOfData )
        {
            try
            {
                tds.discardResultSetOld(context);
                hitEndOfData = true;
                if( allowTdsRelease )
                    stmt.releaseTds();
            }
            catch( TdsException e )
            {
                e.printStackTrace();
                exception = e;
            }
            catch( java.io.IOException e )
            {
                e.printStackTrace();
                exception = e;
            }
        }

        rowCache = null;
        metaData = null;
        context = null;
        stmt = null;
        tds = null;

        if( exception != null )
            throw new SQLException(exception.toString());
    }

    /**
     *  A ResultSet is initially positioned before its first row; the first call
     *  to next makes the first row the current row; the second call makes the
     *  second row the current row, etc. <P>
     *
     *  If an input stream from the previous row is open, it is implicitly
     *  closed. The ResultSet's warning chain is cleared when a new row is read.
     *
     *@return                   true if the new current row is valid; false if
     *      there are no more rows
     *@exception  SQLException  if a database-access error occurs.
     */
    public synchronized boolean next() throws SQLException
    {
        if( isClosed )
            throw new SQLException("result set is closed");

        if( haveMoreResults() )
        {
            rowIndex++;
            row++;
            return true;
        }

        return false;
    }

    /** @todo fetchNextRow should not be public! Possible synchronization problem! */
    public PacketRowResult fetchNextRow() throws SQLException
    {
        boolean wasCanceled = false;
        PacketRowResult row = null;

        try
        {
            clearWarnings();

            // Keep eating garbage and warnings until we reach the next result
            while( !tds.isResultSet() &&
                   !tds.isEndOfResults() &&
                   !tds.isResultRow())
            {
                // RMK 2000-06-08: don't choke on RET_STAT package.
                if( tds.isProcId() || tds.isRetStat() )
                    tds.processSubPacket();
                else if( tds.isParamResult() )
                {
                    PacketResult tmp1 = tds.processSubPacket();

                    if( stmt!=null && stmt instanceof CallableStatement_base )
                        ((CallableStatement_base)stmt).addOutputParam(
                            ((PacketOutputParamResult)tmp1).getValue());
                }
                /*
                else if (tds.isTextUpdate()) {
                    PacketResult tmp1 =
                            (PacketResult) tds.processSubPacket();
                }
                 */
                else if( tds.isMessagePacket() || tds.isErrorPacket() )
                    warningChain.addOrReturn(
                        (PacketMsgResult)tds.processSubPacket());
                else
                    throw new SQLException("Protocol confusion. "
                        + "Got a 0x"
                        + Integer.toHexString((tds.peek() & 0xff))
                        + " packet");
            }

            stmt.eofResults();
            warningChain.checkForExceptions();

            if( tds.isResultRow() )
                row = (PacketRowResult)tds.processSubPacket(context);
            else if( tds.isEndOfResults() )
            {
                wasCanceled = ((PacketEndTokenResult)
                    tds.processSubPacket(context)).wasCanceled();
                row = null;
                hitEndOfData = true;
                stmt.eofResults();
            }
            else if( !tds.isResultSet() )
                throw new SQLException("Protocol confusion. "
                    + "Got a 0x"
                    + Integer.toHexString((tds.peek() & 0xff))
                    + " packet");
        }
        catch( java.io.IOException e )
        {
            stmt.eofResults();
            throw new SQLException(e.getMessage());
        }
        catch( TdsException e )
        {
            stmt.eofResults();
            e.printStackTrace();
            throw new SQLException(e.getMessage());
        }

        if( wasCanceled )
            throw new SQLException("Query was canceled or timed out.");

        return row;
    }


    public void beforeFirst() throws SQLException
    {
        throw new SQLException("The result set type is TYPE_FORWARD_ONLY");
    }

    /**
     *  JDBC 2.0 <p>
     *
     *  Moves the cursor to the end of the result set, just after the last row.
     *  Has no effect if the result set contains no rows.
     *
     *@exception  SQLException  if a database access error occurs or the result
     *      set type is TYPE_FORWARD_ONLY
     */
    public void afterLast() throws SQLException
    {
        throw new SQLException("The result set type is TYPE_FORWARD_ONLY");
    }

    /**
     *  JDBC 2.0 <p>
     *
     *  Moves the cursor to the first row in the result set.
     *
     *@return                   true if the cursor is on a valid row; false if
     *      there are no rows in the result set
     *@exception  SQLException  if a database access error occurs or the result
     *      set type is TYPE_FORWARD_ONLY
     */
    public boolean first() throws SQLException
    {
        throw new SQLException("The result set type is TYPE_FORWARD_ONLY");
    }

    /**
     *  JDBC 2.0 <p>
     *
     *  Moves the cursor to the last row in the result set.
     *
     *@return                   true if the cursor is on a valid row; false if
     *      there are no rows in the result set
     *@exception  SQLException  if a database access error occurs or the result
     *      set type is TYPE_FORWARD_ONLY.
     */
    public boolean last() throws SQLException
    {
        throw new SQLException("The result set type is TYPE_FORWARD_ONLY");
    }

    /**
     *  JDBC 2.0 <p>
     *
     *  Moves the cursor to the given row number in the result set. <p>
     *
     *  If the row number is positive, the cursor moves to the given row number
     *  with respect to the beginning of the result set. The first row is row 1,
     *  the second is row 2, and so on. <p>
     *
     *  If the given row number is negative, the cursor moves to an absolute row
     *  position with respect to the end of the result set. For example, calling
     *  <code>absolute(-1)</code> positions the cursor on the last row, <code>
     *  absolute(-2)</code> indicates the next-to-last row, and so on. <p>
     *
     *  An attempt to position the cursor beyond the first/last row in the
     *  result set leaves the cursor before/after the first/last row,
     *  respectively. <p>
     *
     *  Note: Calling <code>absolute(1)</code> is the same as calling <code>
     *  first()</code> . Calling <code>absolute(-1)</code> is the same as
     *  calling <code>last()</code> .
     *
     *@param  row
     *@return                   true if the cursor is on the result set; false
     *      otherwise
     *@exception  SQLException  if a database access error occurs or row is 0,
     *      or result set type is TYPE_FORWARD_ONLY.
     */
    public boolean absolute(int row) throws SQLException
    {
        throw new SQLException("The result set type is TYPE_FORWARD_ONLY");
    }

    /**
     *  JDBC 2.0 <p>
     *
     *  Moves the cursor a relative number of rows, either positive or negative.
     *  Attempting to move beyond the first/last row in the result set positions
     *  the cursor before/after the the first/last row. Calling <code>
     *  relative(0)</code> is valid, but does not change the cursor position.
     *  <p>
     *
     *  Note: Calling <code>relative(1)</code> is different from calling <code>
     *  next()</code> because is makes sense to call <code>next()</code> when
     *  there is no current row, for example, when the cursor is positioned
     *  before the first row or after the last row of the result set.
     *
     *@param  rows
     *@return                   true if the cursor is on a row; false otherwise
     *@exception  SQLException  if a database access error occurs, there is no
     *      current row, or the result set type is TYPE_FORWARD_ONLY
     */
    public boolean relative(int rows) throws SQLException
    {
        throw new SQLException("The result set type is TYPE_FORWARD_ONLY");
    }

    /**
     *  JDBC 2.0 <p>
     *
     *  Moves the cursor to the previous row in the result set. <p>
     *
     *  Note: <code>previous()</code> is not the same as <code>relative(-1)
     *  </code>because it makes sense to call</code> previous()</code> when
     *  there is no current row.
     *
     *@return                   true if the cursor is on a valid row; false if
     *      it is off the result set
     *@exception  SQLException  if a database access error occurs or the result
     *      set type is TYPE_FORWARD_ONLY
     */
    public boolean previous() throws SQLException
    {
        throw new SQLException("The result set type is TYPE_FORWARD_ONLY");
    }

    /**
     *  JDBC 2.0 Indicates whether the current row has been updated. The value
     *  returned depends on whether or not the result set can detect updates.
     *
     *@return                   true if the row has been visibly updated by the
     *      owner or another, and updates are detected
     *@exception  SQLException  if a database access error occurs
     *@see                      DatabaseMetaData#updatesAreDetected
     */
    public boolean rowUpdated() throws SQLException
    {
        return false;
    }

    /**
     *  JDBC 2.0 Indicates whether the current row has had an insertion. The
     *  value returned depends on whether or not the result set can detect
     *  visible inserts.
     *
     *@return                   true if a row has had an insertion and
     *      insertions are detected
     *@exception  SQLException  if a database access error occurs
     *@see                      DatabaseMetaData#insertsAreDetected
     */
    public boolean rowInserted() throws SQLException
    {
        return false;
    }

    /**
     *  JDBC 2.0 Indicates whether a row has been deleted. A deleted row may
     *  leave a visible "hole" in a result set. This method can be used to
     *  detect holes in a result set. The value returned depends on whether or
     *  not the result set can detect deletions.
     *
     *@return                   true if a row was deleted and deletions are
     *      detected
     *@exception  SQLException  if a database access error occurs
     *@see                      DatabaseMetaData#deletesAreDetected
     */
    public boolean rowDeleted() throws SQLException
    {
        return false;
    }

    /**
     *  JDBC 2.0 Inserts the contents of the insert row into the result set and
     *  the database. Must be on the insert row when this method is called.
     *
     *@exception  SQLException  if a database access error occurs, if called
     *      when not on the insert row, or if not all of non-nullable columns in
     *      the insert row have been given a value
     */
    public void insertRow() throws SQLException
    {
        throw new SQLException("ResultSet is not updateable");
    }

    /**
     *  JDBC 2.0 Updates the underlying database with the new contents of the
     *  current row. Cannot be called when on the insert row.
     *
     *@exception  SQLException  if a database access error occurs or if called
     *      when on the insert row
     */
    public void updateRow() throws SQLException
    {
        throw new SQLException("ResultSet is not updateable");
    }

    /**
     *  JDBC 2.0 Deletes the current row from the result set and the underlying
     *  database. Cannot be called when on the insert row.
     *
     *@exception  SQLException  if a database access error occurs or if called
     *      when on the insert row.
     */
    public void deleteRow() throws SQLException
    {
        throw new SQLException("ResultSet is not updateable");
    }

    /**
     *  JDBC 2.0 Refreshes the current row with its most recent value in the
     *  database. Cannot be called when on the insert row. The <code>refreshRow
     *  </code>method provides a way for an application to explicitly tell the
     *  JDBC driver to refetch a row(s) from the database. An application may
     *  want to call <code>refreshRow</code> when caching or prefetching is
     *  being done by the JDBC driver to fetch the latest value of a row from
     *  the database. The JDBC driver may actually refresh multiple rows at once
     *  if the fetch size is greater than one. All values are refetched subject
     *  to the transaction isolation level and cursor sensitivity. If <code>
     *  refreshRow</code> is called after calling <code>updateXXX</code> , but
     *  before calling <code>updateRow</code> , then the updates made to the row
     *  are lost. Calling the method <code>refreshRow</code> frequently will
     *  likely slow performance.
     *
     *@exception  SQLException  if a database access error occurs or if called
     *      when on the insert row
     */
    public void refreshRow() throws SQLException
    {
        //No effect
    }

    /**
     *  JDBC 2.0 Cancels the updates made to a row. This method may be called
     *  after calling an <code>updateXXX</code> method(s) and before calling
     *  <code>updateRow</code> to rollback the updates made to a row. If no
     *  updates have been made or <code>updateRow</code> has already been
     *  called, then this method has no effect.
     *
     *@exception  SQLException  if a database access error occurs or if called
     *      when on the insert row
     */
    public void cancelRowUpdates() throws SQLException
    {
        //No effect
    }

    /**
     *  JDBC 2.0 Moves the cursor to the insert row. The current cursor position
     *  is remembered while the cursor is positioned on the insert row. The
     *  insert row is a special row associated with an updatable result set. It
     *  is essentially a buffer where a new row may be constructed by calling
     *  the <code>updateXXX</code> methods prior to inserting the row into the
     *  result set. Only the <code>updateXXX</code> , <code>getXXX</code> , and
     *  <code>insertRow</code> methods may be called when the cursor is on the
     *  insert row. All of the columns in a result set must be given a value
     *  each time this method is called before calling <code>insertRow</code> .
     *  The method <code>updateXXX</code> must be called before a <code>getXXX
     *  </code>method can be called on a column value.
     *
     *@exception  SQLException  if a database access error occurs or the result
     *      set is not updatable
     */
    public void moveToInsertRow() throws SQLException
    {
        throw new SQLException("ResultSet is not updateable");
    }

    /**
     *  JDBC 2.0 Moves the cursor to the remembered cursor position, usually the
     *  current row. This method has no effect if the cursor is not on the
     *  insert row.
     *
     *@exception  SQLException  if a database access error occurs or the result
     *      set is not updatable
     */
    public void moveToCurrentRow() throws SQLException
    {
        throw new SQLException("ResultSet is not updateable");
    }

    public synchronized PacketRowResult currentRow() throws SQLException
    {
        if( rowIndex < 0 )
            throw new SQLException("No current row in the ResultSet");
        else if( rowIndex >= rowCount )
            throw new SQLException("No more results in ResultSet");
        return rowCache[rowIndex];
    }

    /**
     * Checks whether there are more results in the cache or, if the cache is
     * empty, gets the next batch of results.
     */
    private boolean haveMoreResults() throws SQLException
    {
        if( rowCount>0 && rowIndex<rowCount-1 )
            return true;

        if( hitEndOfData )
        {
            rowCount = 0;
            return false;
        }

        // Get next batch of results
        return internalFetchRows() > 0;
    }

    /**
     * Caches the next lot of rows, and returns the number of rows cached.
     * The current row is lost! Use with care!
     *
     * @return    the number of rows cached
     * @exception SQLException if an SQL error occurs
     */
    private int internalFetchRows() throws SQLException
    {
        // Need to set this so that next() will set it to 0
        rowCount = 0;

        do
        {
            PacketRowResult row = fetchNextRow();
            if( hitEndOfData )
                break;

            rowCache[rowCount] = row;
            rowCount++;
            // Not very efficient, but this should be set only if we got a row
            rowIndex = -1;
        } while (rowCount < fetchSize);

        return rowCount;
    }

    /**
     * Increases the size of the internal cache (keeping its contents).
     */
    private void reallocCache()
    {
        if( rowCache.length == fetchSize )
        {
            PacketRowResult[] newCache = new PacketRowResult[fetchSize*2];
            System.arraycopy(rowCache, 0, newCache, 0, rowCache.length);
            rowCache = newCache;
            fetchSize *= 2;
        }
        else
            // Make it as large as possible, it doesn't matter since we only do
            // this when fetching all rows into the cache.
            fetchSize = rowCache.length;
    }

    /**
     * Caches all unread rows into the internal cache, enlarging it as
     * necessary. Can be quite memory-consuming, depending on result size.
     */
    synchronized void fetchIntoCache() throws SQLException
    {
        if( rowCount == 0 )
            internalFetchRows();

        if( hitEndOfData )
            return;

        if( rowIndex>0 && rowIndex<rowCount)
        {
            System.arraycopy(rowCache,rowIndex,rowCache,0,rowCount-rowIndex);
            rowCount -= rowIndex;
            rowIndex = 0;
        }
        // Only if the cache is full
        else if( rowIndex<=0 && rowCount==fetchSize )
            reallocCache();

        while (!hitEndOfData)
        {
            do
            {
                PacketRowResult row = fetchNextRow();
                if( hitEndOfData )
                    break;

                rowCache[rowCount] = row;
                rowCount++;
            } while( rowCount < fetchSize );

            if( !hitEndOfData )
                reallocCache();
        }
    }
}
