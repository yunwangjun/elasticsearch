/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package hdfs;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeysPublic;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.permission.AclEntry;
import org.apache.hadoop.fs.permission.AclEntryType;
import org.apache.hadoop.fs.permission.FsAction;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.security.UserGroupInformation;

/**
 * MiniHDFS test fixture. There is a CLI tool, but here we can
 * easily properly setup logging, avoid parsing JSON, etc.
 */
public class MiniHDFS {

    private static String PORT_FILE_NAME = "ports";
    private static String PID_FILE_NAME = "pid";

    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 3) {
            throw new IllegalArgumentException("Expected: MiniHDFS <baseDirectory> [<kerberosPrincipal> <kerberosKeytab>], " +
                "got: " + Arrays.toString(args));
        }
        boolean secure = args.length == 3;

        // configure Paths
        Path baseDir = Paths.get(args[0]);
        // hadoop-home/, so logs will not complain
        if (System.getenv("HADOOP_HOME") == null) {
            Path hadoopHome = baseDir.resolve("hadoop-home");
            Files.createDirectories(hadoopHome);
            System.setProperty("hadoop.home.dir", hadoopHome.toAbsolutePath().toString());
        }
        // hdfs-data/, where any data is going
        Path hdfsHome = baseDir.resolve("hdfs-data");

        // configure cluster
        Configuration cfg = new Configuration();
        cfg.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR, hdfsHome.toAbsolutePath().toString());
        // lower default permission: TODO: needed?
        cfg.set(DFSConfigKeys.DFS_DATANODE_DATA_DIR_PERMISSION_KEY, "766");

        // optionally configure security
        if (secure) {
            String kerberosPrincipal = args[1];
            String keytabFile = args[2];

            cfg.set(CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHENTICATION, "kerberos");
            cfg.set(CommonConfigurationKeysPublic.HADOOP_SECURITY_AUTHORIZATION, "true");
            cfg.set(DFSConfigKeys.DFS_NAMENODE_KERBEROS_PRINCIPAL_KEY, kerberosPrincipal);
            cfg.set(DFSConfigKeys.DFS_DATANODE_KERBEROS_PRINCIPAL_KEY, kerberosPrincipal);
            cfg.set(DFSConfigKeys.DFS_WEB_AUTHENTICATION_KERBEROS_PRINCIPAL_KEY, kerberosPrincipal);
            cfg.set(DFSConfigKeys.DFS_NAMENODE_KEYTAB_FILE_KEY, keytabFile);
            cfg.set(DFSConfigKeys.DFS_DATANODE_KEYTAB_FILE_KEY, keytabFile);
            cfg.set(DFSConfigKeys.DFS_NAMENODE_ACLS_ENABLED_KEY, "true");
            cfg.set(DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_ENABLE_KEY, "true");
            cfg.set(DFSConfigKeys.IGNORE_SECURE_PORTS_FOR_TESTING_KEY, "true");
        }

        UserGroupInformation.setConfiguration(cfg);

        // TODO: remove hardcoded port!
        MiniDFSCluster.Builder builder = new MiniDFSCluster.Builder(cfg);
        if (secure) {
            builder.nameNodePort(9998);
        } else {
            builder.nameNodePort(9999);
        }
        MiniDFSCluster dfs = builder.build();

        // Set the elasticsearch user directory up
        if (UserGroupInformation.isSecurityEnabled()) {
            FileSystem fs = dfs.getFileSystem();
            org.apache.hadoop.fs.Path esUserPath = new org.apache.hadoop.fs.Path("/user/elasticsearch");
            fs.mkdirs(esUserPath);
            List<AclEntry> acls = new ArrayList<>();
            acls.add(new AclEntry.Builder().setType(AclEntryType.USER).setName("elasticsearch").setPermission(FsAction.ALL).build());
            fs.modifyAclEntries(esUserPath, acls);
            fs.close();
        }

        // write our PID file
        Path tmp = Files.createTempFile(baseDir, null, null);
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        Files.write(tmp, pid.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, baseDir.resolve(PID_FILE_NAME), StandardCopyOption.ATOMIC_MOVE);

        // write our port file
        tmp = Files.createTempFile(baseDir, null, null);
        Files.write(tmp, Integer.toString(dfs.getNameNodePort()).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, baseDir.resolve(PORT_FILE_NAME), StandardCopyOption.ATOMIC_MOVE);
    }
}
