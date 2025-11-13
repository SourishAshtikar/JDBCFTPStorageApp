import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.sql.*;
import org.apache.commons.net.ftp.*;
import java.util.Arrays;

public class JDBCFTPStorageApp extends JFrame {

    private static final String DB_URL = "jdbc:postgresql://CHANGE_ME";
    private static final String DB_USER = "CHANGE_ME";
    private static final String DB_PASS = "CHANGE_ME";
    private static final String FTP_HOST = "localhost";
    private static final int FTP_PORT = 21;
    private static final String FTP_USER = "CHANGE_ME";
    private static final String FTP_PASS = "CHANGE_ME";
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JButton registerButton, loginButton;

    public JDBCFTPStorageApp() {
        setTitle("FTP Storage App");
        setSize(300, 180);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new GridLayout(3, 2));

        add(new JLabel("Username:"));
        usernameField = new JTextField();
        add(usernameField);

        add(new JLabel("Password:"));
        passwordField = new JPasswordField();
        add(passwordField);

        registerButton = new JButton("Register");
        loginButton = new JButton("Login");
        add(registerButton);
        add(loginButton);

        registerButton.addActionListener(e -> registerUser());
        loginButton.addActionListener(e -> loginUser());

        setVisible(true);
    }

    private Connection connectDB() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    private FTPClient connectFTP() throws IOException {
        FTPClient ftp = new FTPClient();
        ftp.setControlEncoding("UTF-8");

        ftp.connect(FTP_HOST, FTP_PORT);
        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
            throw new IOException("FTP connection failed: " + ftp.getReplyString());
        }

        if (!ftp.login(FTP_USER, FTP_PASS)) {
            throw new IOException("FTP login failed: Invalid FTP credentials.");
        }

        ftp.enterLocalActiveMode(); 
        ftp.setFileType(FTP.BINARY_FILE_TYPE);
        return ftp;
    }

    private void closeFTP(FTPClient ftp) {
        if (ftp != null && ftp.isConnected()) {
            try {
                ftp.logout();
                ftp.disconnect();
            } catch (IOException e) {}
        }
    }

    private void registerUser() {
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());

        if (username.isEmpty() || password.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please fill all fields!");
            return;
        }

        Connection conn = null;
        FTPClient ftp = null;

        try {
            conn = connectDB();

            PreparedStatement checkStmt = conn.prepareStatement("SELECT * FROM users WHERE username=?");
            checkStmt.setString(1, username);

            if (checkStmt.executeQuery().next()) {
                JOptionPane.showMessageDialog(this, "User already exists!");
                return;
            }

            PreparedStatement insertStmt = conn.prepareStatement(
                    "INSERT INTO users(username, password_hash, folder_name) VALUES (?, ?, ?)");
            insertStmt.setString(1, username);
            insertStmt.setString(2, password);
            insertStmt.setString(3, username);
            insertStmt.executeUpdate();

            ftp = connectFTP();
            ftp.makeDirectory(username);

            JOptionPane.showMessageDialog(this, "Registration successful!");

        } catch (SQLException | IOException e) {
            JOptionPane.showMessageDialog(this, "Error: " + e.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
            closeFTP(ftp);
        }
    }

    private void loginUser() {
        String username = usernameField.getText().trim();
        String password = new String(passwordField.getPassword());

        try (Connection conn = connectDB()) {
            PreparedStatement stmt = conn.prepareStatement("SELECT password_hash, folder_name FROM users WHERE username=?");
            stmt.setString(1, username);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                if (rs.getString("password_hash").equals(password)) {
                    JOptionPane.showMessageDialog(this, "Login successful!");
                    openStorageUI(username, rs.getString("folder_name"));
                    this.dispose();
                } else {
                    JOptionPane.showMessageDialog(this, "Invalid password!");
                }
            } else {
                JOptionPane.showMessageDialog(this, "User not found!");
            }

        } catch (SQLException e) {
            JOptionPane.showMessageDialog(this, "Database error: " + e.getMessage());
        }
    }

    private void logout(JFrame storageFrame) {
        storageFrame.dispose();
        new JDBCFTPStorageApp().setVisible(true);
    }
    private String sanitizePath(String fileName) {
        if (fileName == null) return null;

        return fileName
                .replace("\\", "/")
                .replace("\uFEFF", "")
                .trim();
    }
    private void openStorageUI(String username, String folderName) {
        JFrame storageFrame = new JFrame("Storage - " + username);
        storageFrame.setSize(500, 100);
        storageFrame.setLayout(new FlowLayout());

        JButton uploadBtn = new JButton("Upload File");
        JButton downloadBtn = new JButton("Download File");
        JButton shareBtn = new JButton("Share File");
        JButton listBtn = new JButton("List Files");
        JButton deleteBtn = new JButton("Delete File");
        JButton logoutBtn = new JButton("Logout");

        storageFrame.add(uploadBtn);
        storageFrame.add(downloadBtn);
        storageFrame.add(shareBtn);
        storageFrame.add(listBtn);
        storageFrame.add(deleteBtn);
        storageFrame.add(logoutBtn);

        uploadBtn.addActionListener(e -> uploadFile(folderName, storageFrame));
        downloadBtn.addActionListener(e -> downloadFile(folderName, storageFrame));
        shareBtn.addActionListener(e -> shareFile(folderName, storageFrame));
        listBtn.addActionListener(e -> listFiles(folderName, storageFrame));
        deleteBtn.addActionListener(e -> deleteFile(folderName, storageFrame));
        logoutBtn.addActionListener(e -> logout(storageFrame));

        storageFrame.setVisible(true);
        storageFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }
    private String pickFileFromFTP(String folderName, JFrame frame) {
        FTPClient ftp = null;

        try {
            ftp = connectFTP();
            ftp.changeWorkingDirectory(folderName);

            String[] fileNames = ftp.listNames();
            closeFTP(ftp);

            if (fileNames == null || fileNames.length == 0) {
                JOptionPane.showMessageDialog(frame, "No files found!");
                return null;
            }

            JList<String> fileList = new JList<>(fileNames);
            fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

            int option = JOptionPane.showConfirmDialog(frame, new JScrollPane(fileList),
                    "Select a file", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

            if (option == JOptionPane.OK_OPTION && !fileList.isSelectionEmpty()) {
                return fileList.getSelectedValue();
            }

        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Error: " + e.getMessage());
        } finally {
            closeFTP(ftp);
        }
        return null;
    }

    private void uploadFile(String folderName, JFrame frame) {

        JFileChooser chooser = new JFileChooser();
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        File selectedFile = chooser.getSelectedFile();
        String fileName = sanitizePath(selectedFile.getName());

        FTPClient ftp = null;

        try (InputStream input = new FileInputStream(selectedFile)) {

            ftp = connectFTP();
            ftp.changeWorkingDirectory(folderName);

            if (ftp.storeFile(fileName, input)) {
                JOptionPane.showMessageDialog(frame, "Uploaded Successfully");
            } else {
                JOptionPane.showMessageDialog(frame, "Upload Failed\n" + ftp.getReplyString());
            }

        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Error: " + e.getMessage());
        } finally {
            closeFTP(ftp);
        }
    }

    private void downloadFile(String folderName, JFrame frame) {
        String remoteFile_org = pickFileFromFTP(folderName, frame);
        String remoteFile = sanitizePath(remoteFile_org);
        if (remoteFile == null) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(remoteFile));

        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        File localFile = chooser.getSelectedFile();

        FTPClient ftp = null;

        try (OutputStream output = new FileOutputStream(localFile)) {
            ftp = connectFTP();
            ftp.changeWorkingDirectory(folderName);
            if (ftp.retrieveFile(remoteFile, output)) {
                JOptionPane.showMessageDialog(frame,
                        "Downloaded to:\n" + localFile.getAbsolutePath());
            } else {
                JOptionPane.showMessageDialog(frame,
                        "Download Failed!\nFTP Reply: " + ftp.getReplyString());
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Error: " + e.getMessage());
        } finally {
            closeFTP(ftp);
        }
    }
    private void shareFile(String folderName, JFrame frame) {
        String fileName = pickFileFromFTP(folderName, frame);
        if (fileName == null) return;
        String cleanName = sanitizePath(fileName);
        if (cleanName == null || cleanName.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Invalid file name!");
            return;
        }
        String recipient = JOptionPane.showInputDialog(frame, "Share with username:");
        if (recipient == null || recipient.trim().isEmpty()) return;
        File temp = null;
        FTPClient ftp = null;
        FTPClient ftpDownload = null;

        try (Connection conn = connectDB()) {
            PreparedStatement stmt = conn.prepareStatement("SELECT folder_name FROM users WHERE username=?");
            stmt.setString(1, recipient);
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                JOptionPane.showMessageDialog(frame, "Recipient not found!");
                return;
            }

            String recipientFolder = rs.getString("folder_name");

            ftpDownload = connectFTP();
            if (!ftpDownload.changeWorkingDirectory(folderName)) {
                JOptionPane.showMessageDialog(frame, "Source folder cannot be accessed!");
                return;
            }

            temp = File.createTempFile("tempShare_", "_" + cleanName);

            try (FileOutputStream fos = new FileOutputStream(temp)) {
                if (!ftpDownload.retrieveFile(cleanName, fos)) {
                    JOptionPane.showMessageDialog(frame, "Failed to download: " + ftpDownload.getReplyString());
                    return;
                }
            }
            closeFTP(ftpDownload);
            ftp = connectFTP();
            if (!ftp.changeWorkingDirectory(recipientFolder)) {
                JOptionPane.showMessageDialog(frame, "Cannot access recipient folder!");
                return;
            }

            try (FileInputStream fis = new FileInputStream(temp)) {
                boolean uploaded = ftp.storeFile(cleanName, fis);

                JOptionPane.showMessageDialog(frame,
                        uploaded ? "Shared successfully!" : "Failed: " + ftp.getReplyString());
            }

        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Error: " + e.getMessage());
        } finally {
            closeFTP(ftp);
            closeFTP(ftpDownload);
            if (temp != null) temp.delete();
        }
    }
    private void deleteFile(String folderName, JFrame frame) {

        String remoteFile = pickFileFromFTP(folderName, frame);
        if (remoteFile == null) return;

        remoteFile = sanitizePath(remoteFile);

        FTPClient ftp = null;

        try {
            ftp = connectFTP();
            ftp.changeWorkingDirectory(folderName);

            if (ftp.deleteFile(remoteFile)) {
                JOptionPane.showMessageDialog(frame, "File Deleted");
            } else {
                JOptionPane.showMessageDialog(frame, "Delete Failed\n" + ftp.getReplyString());
            }

        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Error: " + e.getMessage());
        } finally {
            closeFTP(ftp);
        }
    }

    private void listFiles(String folderName, JFrame frame) {
        FTPClient ftp = null;
        try {
            ftp = connectFTP();
            if (!ftp.changeWorkingDirectory(folderName)) {
                JOptionPane.showMessageDialog(frame, "Error: Could not access folder '" + folderName + "'. Check permissions.");
                return;
            }
            String[] fileNames = ftp.listNames();
            StringBuilder fileList = new StringBuilder("Files in your directory:\n");
            boolean foundFiles = false;
            System.out.println("DEBUG: Raw file names array: " + Arrays.toString(fileNames));
            int initialLength = fileList.length();
            System.out.println(initialLength);
            if (fileNames != null && fileNames.length > 0) {
                for (String name : fileNames) {
                    String trimmedName = name.trim();
                    if (!trimmedName.isEmpty() &&
                            !trimmedName.equals(".") &&
                            !trimmedName.equals("..") &&
                            !trimmedName.startsWith(".")) {
                        fileList.append("- ").append(trimmedName).append("\n");
                        foundFiles = true;
                    }
                }

            }if (fileList.length() <=29) {
                JOptionPane.showMessageDialog(frame, "No files found!");
                return;
            }
            System.out.println(fileList.length());
            JTextArea textArea = new JTextArea(fileList.toString(), 15, 30);
            textArea.setEditable(false);
            JOptionPane.showMessageDialog(frame, new JScrollPane(textArea), "Directory Listing - " + folderName, JOptionPane.PLAIN_MESSAGE);

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Error listing files: " + ex.getMessage());
        } finally {
            closeFTP(ftp);
        }
    }
    public static void main(String[] args) {
        new JDBCFTPStorageApp();
    }
}