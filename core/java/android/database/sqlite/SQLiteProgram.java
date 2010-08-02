/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.database.sqlite;

import android.database.DatabaseUtils;
import android.util.Log;
import android.util.Pair;

import java.util.ArrayList;

/**
 * A base class for compiled SQLite programs.
 *<p>
 * SQLiteProgram is not internally synchronized so code using a SQLiteProgram from multiple
 * threads should perform its own synchronization when using the SQLiteProgram.
 */
public abstract class SQLiteProgram extends SQLiteClosable {

    private static final String TAG = "SQLiteProgram";

    /** The database this program is compiled against.
     * @deprecated do not use this
     */
    @Deprecated
    protected SQLiteDatabase mDatabase;

    /** The SQL used to create this query */
    /* package */ final String mSql;

    /**
     * Native linkage, do not modify. This comes from the database and should not be modified
     * in here or in the native code.
     * @deprecated do not use this
     */
    @Deprecated
    protected int nHandle = 0;

    /**
     * the SQLiteCompiledSql object for the given sql statement.
     */
    private SQLiteCompiledSql mCompiledSql;

    /**
     * SQLiteCompiledSql statement id is populated with the corresponding object from the above
     * member. This member is used by the native_bind_* methods
     * @deprecated do not use this
     */
    @Deprecated
    protected int nStatement = 0;

    /**
     * In the case of {@link SQLiteStatement}, this member stores the bindargs passed
     * to the following methods, instead of actually doing the binding.
     * <ul>
     *   <li>{@link #bindBlob(int, byte[])}</li>
     *   <li>{@link #bindDouble(int, double)}</li>
     *   <li>{@link #bindLong(int, long)}</li>
     *   <li>{@link #bindNull(int)}</li>
     *   <li>{@link #bindString(int, String)}</li>
     * </ul>
     * <p>
     * Each entry in the array is a Pair of
     * <ol>
     *   <li>bind arg position number</li>
     *   <li>the value to be bound to the bindarg</li>
     * </ol>
     * <p>
     * It is lazily initialized in the above bind methods
     * and it is cleared in {@link #clearBindings()} method.
     * <p>
     * It is protected (in multi-threaded environment) by {@link SQLiteProgram}.this
     */
    private ArrayList<Pair<Integer, Object>> mBindArgs = null;

    /* package */ final int mStatementType;

    /* package */ SQLiteProgram(SQLiteDatabase db, String sql) {
        this(db, sql, true);
    }

    /* package */ SQLiteProgram(SQLiteDatabase db, String sql, boolean compileFlag) {
        mSql = sql.trim();
        mStatementType = DatabaseUtils.getSqlStatementType(mSql);
        db.acquireReference();
        db.addSQLiteClosable(this);
        mDatabase = db;
        nHandle = db.mNativeHandle;
        if (compileFlag) {
            compileSql();
        }
    }

    private void compileSql() {
        // only cache CRUD statements
        if (mStatementType != DatabaseUtils.STATEMENT_SELECT &&
                mStatementType != DatabaseUtils.STATEMENT_UPDATE) {
            mCompiledSql = new SQLiteCompiledSql(mDatabase, mSql);
            nStatement = mCompiledSql.nStatement;
            // since it is not in the cache, no need to acquire() it.
            return;
        }

        mCompiledSql = mDatabase.getCompiledStatementForSql(mSql);
        if (mCompiledSql == null) {
            // create a new compiled-sql obj
            mCompiledSql = new SQLiteCompiledSql(mDatabase, mSql);

            // add it to the cache of compiled-sqls
            // but before adding it and thus making it available for anyone else to use it,
            // make sure it is acquired by me.
            mCompiledSql.acquire();
            mDatabase.addToCompiledQueries(mSql, mCompiledSql);
            if (SQLiteDebug.DEBUG_ACTIVE_CURSOR_FINALIZATION) {
                Log.v(TAG, "Created DbObj (id#" + mCompiledSql.nStatement +
                        ") for sql: " + mSql);
            }
        } else {
            // it is already in compiled-sql cache.
            // try to acquire the object.
            if (!mCompiledSql.acquire()) {
                int last = mCompiledSql.nStatement;
                // the SQLiteCompiledSql in cache is in use by some other SQLiteProgram object.
                // we can't have two different SQLiteProgam objects can't share the same
                // CompiledSql object. create a new one.
                // finalize it when I am done with it in "this" object.
                mCompiledSql = new SQLiteCompiledSql(mDatabase, mSql);
                if (SQLiteDebug.DEBUG_ACTIVE_CURSOR_FINALIZATION) {
                    Log.v(TAG, "** possible bug ** Created NEW DbObj (id#" +
                            mCompiledSql.nStatement +
                            ") because the previously created DbObj (id#" + last +
                            ") was not released for sql:" + mSql);
                }
                // since it is not in the cache, no need to acquire() it.
            }
        }
        nStatement = mCompiledSql.nStatement;
    }

    @Override
    protected void onAllReferencesReleased() {
        releaseCompiledSqlIfNotInCache();
        mDatabase.removeSQLiteClosable(this);
        mDatabase.releaseReference();
    }

    @Override
    protected void onAllReferencesReleasedFromContainer() {
        releaseCompiledSqlIfNotInCache();
        mDatabase.releaseReference();
    }

    /* package */ synchronized void releaseCompiledSqlIfNotInCache() {
        if (mCompiledSql == null) {
            return;
        }
        synchronized(mDatabase.mCompiledQueries) {
            if (!mDatabase.mCompiledQueries.containsValue(mCompiledSql)) {
                // it is NOT in compiled-sql cache. i.e., responsibility of
                // releasing this statement is on me.
                mCompiledSql.releaseSqlStatement();
            } else {
                // it is in compiled-sql cache. reset its CompiledSql#mInUse flag
                mCompiledSql.release();
            }
        }
        mCompiledSql = null;
        nStatement = 0;
    }

    /**
     * Returns a unique identifier for this program.
     *
     * @return a unique identifier for this program
     * @deprecated do not use this method. it is not guaranteed to be the same across executions of
     * the SQL statement contained in this object.
     */
    @Deprecated
    public final int getUniqueId() {
      return -1;
    }

    /**
     * used only for testing purposes
     */
    /* package */ int getSqlStatementId() {
      synchronized(this) {
        return (mCompiledSql == null) ? 0 : nStatement;
      }
    }

    /* package */ String getSqlString() {
        return mSql;
    }

    /**
     * @deprecated This method is deprecated and must not be used.
     *
     * @param sql the SQL string to compile
     * @param forceCompilation forces the SQL to be recompiled in the event that there is an
     *  existing compiled SQL program already around
     */
    @Deprecated
    protected void compile(String sql, boolean forceCompilation) {
        // TODO is there a need for this?
    }

    /**
     * Bind a NULL value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind null to
     */
    public void bindNull(int index) {
        mDatabase.verifyDbIsOpen();
        synchronized (this) {
            acquireReference();
            try {
                if (this.nStatement == 0) {
                    // since the SQL statement is not compiled, don't do the binding yet.
                    // can be done before executing the SQL statement
                    addToBindArgs(index, null);
                } else {
                    native_bind_null(index);
                }
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Bind a long value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind
     */
    public void bindLong(int index, long value) {
        mDatabase.verifyDbIsOpen();
        synchronized (this) {
            acquireReference();
            try {
                if (this.nStatement == 0) {
                    addToBindArgs(index, value);
                } else {
                    native_bind_long(index, value);
                }
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Bind a double value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind
     */
    public void bindDouble(int index, double value) {
        mDatabase.verifyDbIsOpen();
        synchronized (this) {
            acquireReference();
            try {
                if (this.nStatement == 0) {
                    addToBindArgs(index, value);
                } else {
                    native_bind_double(index, value);
                }
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Bind a String value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind
     */
    public void bindString(int index, String value) {
        if (value == null) {
            throw new IllegalArgumentException("the bind value at index " + index + " is null");
        }
        mDatabase.verifyDbIsOpen();
        synchronized (this) {
            acquireReference();
            try {
                if (this.nStatement == 0) {
                    addToBindArgs(index, value);
                } else {
                    native_bind_string(index, value);
                }
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Bind a byte array value to this statement. The value remains bound until
     * {@link #clearBindings} is called.
     *
     * @param index The 1-based index to the parameter to bind
     * @param value The value to bind
     */
    public void bindBlob(int index, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("the bind value at index " + index + " is null");
        }
        mDatabase.verifyDbIsOpen();
        synchronized (this) {
            acquireReference();
            try {
                if (this.nStatement == 0) {
                    addToBindArgs(index, value);
                } else {
                    native_bind_blob(index, value);
                }
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Clears all existing bindings. Unset bindings are treated as NULL.
     */
    public void clearBindings() {
        synchronized (this) {
            mBindArgs = null;
            if (this.nStatement == 0) {
                return;
            }
            mDatabase.verifyDbIsOpen();
            acquireReference();
            try {
                native_clear_bindings();
            } finally {
                releaseReference();
            }
        }
    }

    /**
     * Release this program's resources, making it invalid.
     */
    public void close() {
        synchronized (this) {
            mBindArgs = null;
            if (nHandle == 0 || !mDatabase.isOpen()) {
                return;
            }
            releaseReference();
        }
    }

    private synchronized void addToBindArgs(int index, Object value) {
        if (mBindArgs == null) {
            mBindArgs = new ArrayList<Pair<Integer, Object>>();
        }
        mBindArgs.add(new Pair<Integer, Object>(index, value));
    }

    /* package */ synchronized void compileAndbindAllArgs() {
        assert nStatement == 0;
        compileSql();
        if (mBindArgs == null) {
            return;
        }
        for (Pair<Integer, Object> p : mBindArgs) {
            if (p.second == null) {
                native_bind_null(p.first);
            } else if (p.second instanceof Long) {
                native_bind_long(p.first, (Long)p.second);
            } else if (p.second instanceof Double) {
                native_bind_double(p.first, (Double)p.second);
            } else if (p.second instanceof byte[]) {
                native_bind_blob(p.first, (byte[])p.second);
            }  else {
                native_bind_string(p.first, (String)p.second);
            }
        }
    }

    /**
     * @deprecated This method is deprecated and must not be used.
     * Compiles SQL into a SQLite program.
     *
     * <P>The database lock must be held when calling this method.
     * @param sql The SQL to compile.
     */
    @Deprecated
    protected final native void native_compile(String sql);

    /**
     * @deprecated This method is deprecated and must not be used.
     */
    @Deprecated
    protected final native void native_finalize();

    protected final native void native_bind_null(int index);
    protected final native void native_bind_long(int index, long value);
    protected final native void native_bind_double(int index, double value);
    protected final native void native_bind_string(int index, String value);
    protected final native void native_bind_blob(int index, byte[] value);
    /* package */ final native void native_clear_bindings();
}

