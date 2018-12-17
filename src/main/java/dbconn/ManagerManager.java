package dbconn;

import api.model.Message;
import dbconn.mongo.MongoManager;
import mail.Mail;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.sql.*;
import java.util.Properties;


public class ManagerManager {
    private PreparedStatement getDBAndPoolStmt;
    private PreparedStatement insertDBStmt;
    private PreparedStatement getCountDBsByPoolStmt;
    private PreparedStatement insertUserStmt;
    private PreparedStatement deleteDBStmt;
    private PreparedStatement deleteDBUsersStmt;

    private Connection managerConnection;
    private DatabaseManager mongo;
//    private DatabaseManager mysql;
//    private DatabaseManager postgres;

    private Mail mail;

    public ManagerManager() {
        mongo = new MongoManager();
//        postgres = new PostgresManager();


        Properties props = new Properties();
        props.setProperty("user", defaults.Secrets.MANAGER_USER);
        props.setProperty("password", defaults.Secrets.MANAGER_PASSWORD);
        props.setProperty("ssl", "true");

        // Connect to the database and prepare statements.
        try {
            managerConnection = DriverManager.getConnection(defaults.Secrets.MANAGER_CONNECT_STRING, props);

            String getPoolDbCount = "select count(*) as total, num_limit as limit, owner "
                    + "from pools, databases "
                    + "where id=pool and id=? and approved "
                    + "group by id ";
            getCountDBsByPoolStmt = managerConnection.prepareStatement(getPoolDbCount);

            String insertDB = "insert into databases "
                    + "(pool, name, purpose, type, approved) "
                    + "values (?, ?, ?, ?, ?)";
            insertDBStmt = managerConnection.prepareStatement(insertDB);

            String getDbAndPoolByName = "select owner, is_group, approved, type"
                    + "from databases, pools"
                    + "where name=? and pool=id";
            getDBAndPoolStmt = managerConnection.prepareStatement(getDbAndPoolByName);

            String insertUser = "insert into users "
                    + "(database, owner, is_group, username, last_reset) "
                    + "values (?, ?, ?, ?, ?)";
            insertUserStmt = managerConnection.prepareStatement(insertUser);

            String deleteDB = "delete from databases where name=?";
            deleteDBStmt = managerConnection.prepareStatement(deleteDB);

            String deleteDBUsers = "delete from users where database=?";
            deleteDBUsersStmt = managerConnection.prepareStatement(deleteDBUsers);

        } catch (SQLException e) {
            // TODO report this in some way? Maybe email someone....
            System.err.println("Manager DB errored while connecting");
            e.printStackTrace();
        }

        mail = new Mail();
    }


    /**
     * Approves a database, creates it, and notifies the owner.
     * @param dbName the name of the database to be approved
     * @return a Message object containing the result of the operation
     */
    public Message approve(String dbName) {
        // Get the db. Check not approved yet. Set approved. Send email, call normal create.
        try {
            getDBAndPoolStmt.setString(1, dbName);
            ResultSet db = getDBAndPoolStmt.executeQuery();
            String owner = db.getString("owner");
            if (db.getBoolean("approved"))
                return new Message("Database already approved.", Message.Type.ERROR);

            create(dbName);
            mail.approve(owner, dbName);
            db.close();

        } catch (SQLException e) {
            e.printStackTrace();
            // TODO specify the type of error? E.g. duplicate names.
            return new Message("SQL error occurred. Try again or check logs.", Message.Type.ERROR);
        }
        return new Message("Database approved.", Message.Type.SUCCESS);
    }


    /**
     * Deletes a standing database request and notifies the requester.
     * @param dbName the database to deny.
     * @return a Message object containing the result of the action.
     */
    public Message deny(String dbName) {
        // Just drop the request.
        try {
            getDBAndPoolStmt.setString(1, dbName);
            ResultSet db = getDBAndPoolStmt.executeQuery();
            if (!db.getBoolean("approved")) {
                String owner = db.getString("owner");
                mail.deny(owner, dbName);

                // Drop it.
                deleteDBStmt.setString(1, dbName);
                deleteDBStmt.execute();
                return new Message("DB request deleted.", Message.Type.SUCCESS);
            } else
                return new Message("DB not awaiting request.", Message.Type.ERROR);
        } catch (SQLException se) {
            se.printStackTrace();
            return new Message("SQL error. Try again or check logs.", Message.Type.ERROR);
        }
    }


    /**
     * Creates a new database.
     * @param dbName The name of the database to be created
     * @return a Message object containing the result of the operation
     */
    private Message create(String dbName) {
        // Get the db. Check approved. Set a password. Call the child create
        try {
            String password = "";
            getDBAndPoolStmt.setString(1, dbName);
            ResultSet db = getDBAndPoolStmt.executeQuery();
            if(db.getBoolean("approved")) {

                // Define a new user account
                insertDBStmt.setString(1, dbName);
                insertDBStmt.setString(4, dbName);
                insertDBStmt.setString(2, db.getString("owner"));
                insertDBStmt.setBoolean(3, db.getBoolean("is_group"));
                insertDBStmt.setDate(5, new java.sql.Date(new java.util.Date().getTime()));
                insertDBStmt.execute();

                // Connect to haddock and generate a password or return an error trying.
                try {
                    URL haddock = new URL("https://haddock.csh.rit.edu/length/32");
                    URLConnection conn = haddock.openConnection();
                    conn.connect();
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    password = br.readLine();
                } catch (IOException e) {
                    e.printStackTrace();
                    return new Message("Failed to generate password.", Message.Type.ERROR);
                }

                if(password.equals(""))
                    return new Message("Failed to generate password.", Message.Type.ERROR);

                // TODO enum.
                switch (db.getInt("type")) {
                    case 0: // Mongo
                        mongo.create(dbName, password);
                        break;
                    case 1: // Postgres
                        break;
                    case 2: // MySQL
                        break;
                    default:
                        return new Message("Unknown database type", Message.Type.ERROR);
                }
            } else {
                return new Message("Specified database not marked as approved.", Message.Type.ERROR);
            }
            db.close();
            if( password.equals("") )
                return new Message("password:" + password, Message.Type.SUCCESS);
        } catch (SQLException se) {
            se.printStackTrace();
            // TODO specify the type of error? E.g. duplicate names.
            return new Message("Create SQL error. Please try again or report to an RTP.", Message.Type.ERROR);
        }
        return new Message("Create failed to generate a password.", Message.Type.ERROR);
    }


    /**
     * Deletes a database.
     * @param dbName the name of the database to delete
     * @return a Message object containing the result of the operation
     */
    public Message delete(String dbName) {
        // Get the db. Delete it. Drop the record. Drop its users.
        try {
            getDBAndPoolStmt.setString(1, dbName);
            ResultSet db = getDBAndPoolStmt.executeQuery();

            // TODO enum.
            switch (db.getInt("type")) {
                case 0: // Mongo
                    mongo.delete(dbName);
                    break;
                case 1: // Postgres
                    break;
                case 2: // MySQL
                    break;
                default:
                    return new Message("Unknown database type", Message.Type.ERROR);
            }

            deleteDBStmt.setString(1, dbName);
            deleteDBStmt.execute();

            deleteDBUsersStmt.setString(1, dbName);
            deleteDBUsersStmt.execute();

        } catch (SQLException e) {
            e.printStackTrace();
            // TODO specify the type of error? E.g. duplicate names.
            return new Message("Delete SQL error. Please try again or report to an RTP.", Message.Type.ERROR);
        }
        return new Message("Database sucessfully deleted.", Message.Type.SUCCESS);
    }


    /**
     * Creates a request for a database. If able to auto approve, also creates the db.
     * @param poolID the id number of the pool this belongs to
     * @param name the name of the new db
     * @param purpose a description of what the db is for
     * @param type the type of db. 0 for mongo, 1 for postgress, 2 for mysql. TODO replace with an enum
     * @return a Message object containing the result of the operation
     */
    public Message request(int poolID, String name, String purpose, int type) {

        Boolean approved = false;
        String owner = null;

        try {
            // Get a count of dbs and check if we should auto approve this request.
            getCountDBsByPoolStmt.setInt(1,poolID);
            ResultSet dbs = getCountDBsByPoolStmt.executeQuery();
            int total_dbs = dbs.getInt("total");
            int limit = dbs.getInt("limit");
            approved = (limit < 0)? true : total_dbs < limit; // If -1, limit is infinity.
            owner = dbs.getString("owner");
            dbs.close();

            // Insert the record into the db
            insertDBStmt.setInt(1, poolID);
            insertDBStmt.setString(2, name);
            insertDBStmt.setString(3, purpose);
            insertDBStmt.setInt(4, type);
            insertDBStmt.setBoolean(5, approved);
            insertDBStmt.execute();

        } catch (SQLException se) {
            se.printStackTrace();
            // TODO specify the type of error? E.g. duplicate names.
            return new Message("Request SQL error. Please try again or report to an RTP.", Message.Type.ERROR);
        }

        if(approved)
            return create(name);
        mail.request(owner, purpose, name);
        return new Message("Database creation request sent.", Message.Type.MESSAGE);
    }


    /**
     * Closes DEaDASS by calling close all of the database connections.
     */
    public void close() {
        try {
            mongo.close();
//            mysql.close();
//            postgres.close();
            managerConnection.close();
        } catch (Exception e) {
            // Ignore, we are shutting down.
        }
    }
}
