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
 * @version $Id: TdsStatement.java,v 1.2 2001-08-31 12:47:20 curthagenlocher Exp $
 */
package com.internetcds.jdbc.tds;

import java.sql.*;


public class TdsStatement implements Statement
{
   public static final String cvsVersion = "$Id: TdsStatement.java,v 1.2 2001-08-31 12:47:20 curthagenlocher Exp $";


   private TdsConnection connection; // The connection who created us
   // ResultSet currentResults = null;     // The current results
   protected SQLWarningChain warningChain; // The warnings chain.
   protected int timeout = 0;              // The timeout for a query

   protected Tds                tds = null;

   protected TdsResultSet          results     = null;
   private   java.sql.ResultSetMetaData  metaResults = null;

   private   boolean            escapeProcessing = true;

   protected int                updateCount = -1;

   private int  maxFieldSize = (1<<31)-1;
   private int  maxRows      = 0;


  /**
   * Constructor for a Statement.  It simply sets the connection
   * that created us.
   *
   * @param connection_ the Connection instantation that creates us
   * @param tds_        a TDS instance to use for communication with server.
   */
   public TdsStatement(
      TdsConnection     connection_,
      Tds        tds_)
      throws SQLException
   {
      tds        = tds_;
      connection = connection_;
      warningChain = new SQLWarningChain();
   }


   private void NotImplemented() throws java.sql.SQLException
      {
         throw new SQLException("Not Implemented");
      }

   protected void finalize()
      throws Throwable
   {
      super.finalize();

      if (tds != null)
      {
         close();
      }
   }



   /**
    * Execute a SQL statement that retruns a single ResultSet
    *
    * @param Sql typically a static SQL SELECT statement
    * @return a ResulSet that contains the data produced by the query
    * @exception SQLException if a database access error occurs
    */
   public ResultSet executeQuery(String sql) throws SQLException
   {
      return internalExecuteQuery( sql );
   }

   /**
    * This is the internal function that all subclasses should call.
    * It is not executeQuery() to allow subclasses (in particular
    * CursorResultSet) to override that functionality without
    * breaking the internal methods.
    */
   final public TdsResultSet internalExecuteQuery(String sql)
   throws SQLException
   {
      if (execute(sql))
      {
         startResultSet();
      }

      return results;
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
   * @param Sql a SQL statement
   * @return either a row count, or 0 for SQL commands
   * @exception SQLException if a database access error occurs
   */

   public int executeUpdate(String sql) throws SQLException
   {
      if (execute(sql))
      {
         startResultSet();
         closeResults();
         throw new SQLException("executeUpdate can't return a result set");
      }
      else
      {
         return getUpdateCount();
      }
   }

   protected void closeResults()
      throws java.sql.SQLException
   {
      if (results != null)
      {
         results.close();
         results = null;
      }
   }

   private void skipToEnd()
      throws java.sql.SQLException, java.io.IOException,
      com.internetcds.jdbc.tds.TdsUnknownPacketSubType,
      com.internetcds.jdbc.tds.TdsException
   {
      boolean       done;
      PacketResult  tmp;

      do
      {
         tmp = tds.processSubPacket();
         done = (tmp instanceof PacketEndTokenResult)
            && (! ((PacketEndTokenResult)tmp).moreResults());
      } while (! done);
   }


   public void commit()
      throws java.sql.SQLException, java.io.IOException, com.internetcds.jdbc.tds.TdsUnknownPacketSubType, com.internetcds.jdbc.tds.TdsException
   {
      String sql = "IF @@TRANCOUNT > 0 COMMIT TRAN ";

      if (tds == null)
      {
         throw new SQLException("Statement is closed");
      }

      internalExecuteQuery(sql);
      skipToEnd();
   }

   public void rollback()
      throws java.sql.SQLException, java.io.IOException, com.internetcds.jdbc.tds.TdsUnknownPacketSubType, com.internetcds.jdbc.tds.TdsException
   {
      String sql = "IF @@TRANCOUNT > 0 ROLLBACK TRAN ";

      if (tds == null)
      {
         throw new SQLException("Statement is closed");
      }

      internalExecuteQuery(sql);
      skipToEnd();
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
   public void close() throws SQLException
   {
      closeResults();

      // Rollback any pending transactions
      try
      {
         rollback();
      }
      catch (com.internetcds.jdbc.tds.TdsUnknownPacketSubType e)
      {
         throw new SQLException("Unknown packet. \n" + e.getMessage());
      }
      catch (com.internetcds.jdbc.tds.TdsException e)
      {
         // XXX
         // ignore this for now
      }
      catch (java.io.IOException e)
      {
         // XXX
         // ignore this for now
      }


      // now we need to relinquish the connection
      if (tds != null)
      {
         Tds tmpTds = tds;
         tds = null;
         try
         {
            ((ConnectionHelper)connection).relinquish(tmpTds);
         }
         catch(TdsException e)
         {
            throw new SQLException("Internal Error: " + e.getMessage());
         }
      }
      try
      {
         ((ConnectionHelper)connection).markAsClosed(this);
      }
      catch(TdsException e)
      {
         throw new SQLException(e.getMessage());
      }
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

   public int getMaxFieldSize() throws SQLException
   {
      return maxFieldSize;
   }

   /**
    * Sets the maxFieldSize
    *
    * @param max the new max column size limit; zero means unlimited
    * @exception SQLException if size exceeds buffer size
    */

   public void setMaxFieldSize(int max) throws SQLException
   {
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

  public int getMaxRows() throws SQLException
   {
      return maxRows;
   }

   /**
    * Set the maximum number of rows
    *
    * @param max the new max rows limit; zero means unlimited
    * @exception SQLException if a database access error occurs
    * @see getMaxRows
    */

   public void setMaxRows(int max) throws SQLException
   {
      if (maxRows < 0)
      {
         throw new SQLException("Negative row count");
      }
      maxRows = max;

      this.executeUpdate("set rowcount " + maxRows);
   }

   /**
    * If escape scanning is on (the default), the driver will do escape
    * substitution before sending the SQL to the database.
    *
    * @param enable true to enable; false to disable
    * @exception SQLException if a database access error occurs
    */

  public void setEscapeProcessing(boolean enable) throws SQLException
   {
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

  public int getQueryTimeout() throws SQLException
   {
      return timeout;
   }

   /**
    * Sets the queryTimeout limit
    *
    * @param seconds - the new query timeout limit in seconds
    * @exception SQLException if a database access error occurs
    */

  public void setQueryTimeout(int seconds) throws SQLException
   {
      timeout = seconds;
   }

  /**
   *
   * @exception SQLException
   */
  public void cancel() throws SQLException
   {
      if (tds == null)
      {
         throw new SQLException("Statement is closed");
      }

      try
      {
         tds.cancel();
      }
      catch(com.internetcds.jdbc.tds.TdsException e)
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
  public SQLWarning getWarnings() throws SQLException
   {
      return warningChain.getWarnings();
   }


  /**
   * After this call, getWarnings returns null until a new warning
   * is reported for this Statement.
   *
   * @exception SQLException if a database access error occurs (why?)
   */
  public void clearWarnings() throws SQLException
   {
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
   *
   * @param name the new cursor name
   * @exception SQLException if a database access error occurs
   */
   public void setCursorName(String name) throws SQLException
   {
      NotImplemented();
   }

   /**
    * @param sql any SQL statement
    * @return true if the next result is a ResulSet, false if it is
    *      an update count or there are no more results
    * @exception SQLException if a database access error occurs
    */
   public boolean execute(String sql) throws SQLException
   {
      SQLException   exception = null;

      if (tds == null)
      {
         throw new SQLException("Statement is closed");
      }


      closeResults();
      clearWarnings();
      updateCount = -1;
      try
      {
         if (escapeProcessing)
         {
            sql = Tds.toNativeSql(sql, tds.getServerType());
         }
         tds.executeQuery(sql, this, timeout);
      }
      catch(java.io.IOException e)
      {
         throw new SQLException("Network error- " + e.getMessage());
      }
      catch(com.internetcds.jdbc.tds.TdsException e)
      {
         throw new SQLException("TDS error- " + e.getMessage());
      }
      return getMoreResults();
   } // execute()


   /**
    * getResultSet returns the current result as a ResultSet.  It
    * should only be called once per result.
    *
    * @return the current result set; null if there are no more
    * @exception SQLException if a database access error occurs
    */
   public java.sql.ResultSet getResultSet() throws SQLException
   {
      try
      {
         if (tds == null)
         {
            throw new SQLException("Statement is closed");
         }
         closeResults();

         if (tds.peek()==TdsDefinitions.TDS_DONEINPROC)
         {
            PacketResult tmp = tds.processSubPacket();
         }

         if (tds.isResultSet())   // JJ 1999-01-09 used be: ;getMoreResults())
         {
            startResultSet();
         }
         else if (updateCount!=-1)
         {
            if (! tds.isEndOfResults())
            {
               // XXX
               throw new SQLException("Internal error.  "+
                                      " expected EndOfResults, found 0x"
                                      + Integer.toHexString(tds.peek()&0xff));
            }
            PacketEndTokenResult end =
               (PacketEndTokenResult) tds.processSubPacket();
            updateCount = end.getRowCount();
            results = null;
         }
         else
         {
            // We didn't have more data and we didn't have an update count,
            // now what?
            throw new SQLException("Internal error.  Confused");
         }
      }
      catch(java.io.IOException e)
      {
         throw new SQLException(e.getMessage());
      }
      catch(TdsException e)
      {
         throw new SQLException(e.getMessage());
      }

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
   public int getUpdateCount() throws SQLException
   {
//        if (updateCount == -1)
//        {
//           throw new SQLException("Don't have a count yet.");
//        }
      // XXX This isn't correct.  We need to check to see if
      // the result was a result set or if there are no more results.
      // If either of those are true we are supposed to return -1
      return updateCount;
   }

   /**
    * getMoreResults moves to a Statement's next result.  If it returns
    * true, this result is a ResulSet.
    *
    * @return true if the next ResultSet is valid
    * @exception SQLException if a database access error occurs
    */
   public boolean getMoreResults() throws SQLException
   {
      SQLException exception = null;
      if (tds == null)
      {
         throw new SQLException("Statement is closed");
      }

      updateCount = -1; // Do we need this global variable?

      if (!tds.moreResults())
      {
         return false;
      }

      closeResults(); // Reset all internal variables (why is this done here?)

      try
      {
         tds.isResultSet();

         // Keep eating garbage and warnings until we reach the next result
         while (!tds.isResultSet() && !tds.isEndOfResults())
         {
            if (tds.isProcId())
            {
               tds.processSubPacket();
            }
            else if (tds.isDoneInProc())
            {
               PacketDoneInProcResult tmp =
                  (PacketDoneInProcResult)tds.processSubPacket();
            }
            else if (tds.isTextUpdate())
            {
               PacketResult tmp1 =
                  (PacketResult)tds.processSubPacket();
            }
            else if (tds.isMessagePacket() || tds.isErrorPacket())
            {
               PacketMsgResult  tmp = (PacketMsgResult)tds.processSubPacket();
               exception = warningChain.addOrReturn(tmp);
            }
            else
            {
               throw new SQLException("Protocol confusion.  "
                                      + "Got a 0x"
                                      + Integer.toHexString((tds.peek() & 0xff))
                                      + " packet");
            }
         } // end while

         if (exception != null)
         {
            try
            {
               tds.discardResultSet(null);
            }
            catch(java.io.IOException e)
            {
               throw new SQLException("Error discarding result set while processing sql error-  " +
                                      exception.getMessage() +
                                      "\nIOException was " +
                                      e.getMessage());
            }
            catch(com.internetcds.jdbc.tds.TdsException e)
            {
               throw new SQLException("Error discarding result set while processing sql error-  " +
                                      exception.getMessage() +
                                      "\nIOException was " +
                                      e.getMessage());
            }
            throw exception;
         }

         if (tds.isEndOfResults())
         {
            PacketEndTokenResult end =
               (PacketEndTokenResult)tds.processSubPacket();
            updateCount = end.getRowCount();
            return false;
         }
         else if (tds.isResultSet())
         {
            return true;
         }
         else
         {
            throw new SQLException("Protocol confusion.  "
                                   + "Got a 0x"
                                   + Integer.toHexString((tds.peek() & 0xff))
                                   + " packet");
         }
      }

      catch(java.io.IOException e)
      {
         throw new SQLException("Network error- " + e.getMessage());
      }
      catch(com.internetcds.jdbc.tds.TdsException e)
      {
         throw new SQLException("TDS error- " + e.getMessage());
      }
   }



   protected void startResultSet( )
      throws SQLException
   {
      Columns      names     = null;
      Columns      info      = null;
      SQLException exception = null;


      try
      {
         while (!tds.isResultRow() && !tds.isEndOfResults())
         {
            PacketResult   tmp = tds.processSubPacket();

            if (tmp.getPacketType() == TdsDefinitions.TDS_DONEINPROC)
            {
               // XXX We should do something with the possible ret_stat
            }
            else if (tmp instanceof PacketColumnNamesResult)
            {
               names = ((PacketColumnNamesResult)tmp).getColumnNames();
            }
            else if (tmp instanceof PacketColumnInfoResult)
            {
               info = ((PacketColumnInfoResult)tmp).getColumnInfo();
            }
            else if (tmp instanceof PacketColumnOrderResult)
            {
               // nop
               // XXX do we want to do anything with this
            }
            else if (tmp instanceof PacketTabNameResult)
            {
               // nop
               // XXX What should be done with this information?
            }
            else if (tmp instanceof PacketControlResult)
            {
               // nop
               // XXX do we want to do anything with this
            }
            else if (tmp instanceof PacketMsgResult)
            {
               exception = warningChain.addOrReturn((PacketMsgResult)tmp);
            }
            else if (tmp instanceof PacketUnknown)
            {
               // XXX Need to add to the warning chain
            }
            else
            {
               throw new SQLException("Trying to get a result set.  Found a "
                                      + tmp.getClass().getName());
            }
         }

         if (exception != null)
         {
            throw exception;
         }
         else if (!tds.isResultRow() && !tds.isEndOfResults())
         {
            // XXX
            throw new SQLException("Confused.  Was expecting a result row.  "
               + "Got a 0x" + Integer.toHexString(tds.peek() & 0xff));
         }

         // TDS 7.0 includes everything in one subpacket.
         if (info != null)
            names.merge(info);

         results = new TdsResultSet( tds, this, names );
      }
      catch(com.internetcds.jdbc.tds.TdsException e)
      {
         e.printStackTrace();
         throw new SQLException(e.getMessage());
      }
      catch( java.io.IOException e)
      {
         e.printStackTrace();
         throw new SQLException(e.getMessage());
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
         NotImplemented();
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
         NotImplemented();
         return 0;
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
         NotImplemented();
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
         NotImplemented();
         return 0;
      }


    /**
     * JDBC 2.0
     *
     * Retrieves the result set concurrency.
     */
   public int getResultSetConcurrency() throws SQLException
      {
         NotImplemented();
         return 0;
      }


    /**
     * JDBC 2.0
     *
     * Determine the result set type.
     */
   public int getResultSetType()  throws SQLException
      {
         NotImplemented();
         return 0;
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
     * @return the connection that produced this statement
     * @exception SQLException if a database access error occurs
     */
   public java.sql.Connection getConnection() throws SQLException
      {
         return connection;
      }


   static public void main(String args[])
      throws java.lang.ClassNotFoundException,
      java.lang.IllegalAccessException,
      java.lang.InstantiationException,
      SQLException
   {

      String query = null;

      String   url = url = ""
         + "jdbc:freetds:"
         + "//"
         + "kap"
         + "/"
         + "pubs";

      Class.forName("com.internetcds.jdbc.tds.Driver").newInstance();
      java.sql.Connection connection;
      connection =  DriverManager.getConnection(url,
                                                "testuser",
                                                "password");
      java.sql.Statement stmt = connection.createStatement();

      query = ""
         + "update titles                                     "
         + "  set price=price+1.00                            "
         + "   where title_id='MC3021' or title_id = 'BU1032' ";
      int count = stmt.executeUpdate(query);
      System.out.println("Updated " + count + " rows.");

      query =
         ""
         +"select price, title_id, title, price*ytd_sales gross from titles"
         +" where title like 'The%'";
      java.sql.ResultSet rs = stmt.executeQuery(query);

      while(rs.next())
      {
         float    price     = rs.getFloat("price");
         if (rs.wasNull())
         {
            System.out.println("price:  null");
         }
         else
         {
            System.out.println("price:  " + price);
         }

         String   title_id  = rs.getString("title_id");
         String   title     = rs.getString("title");
         float    gross     = rs.getFloat("gross");


         System.out.println("id:     " + title_id);
         System.out.println("name:   " + title);
         System.out.println("gross:  " + gross);
         System.out.println("");
      }
   }
}



