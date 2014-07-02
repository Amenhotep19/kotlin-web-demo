/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.vfs.impl.jar;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.lang.ref.SoftReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class JarHandlerBase {
    protected ZipInputStream myZipFile = null;
    protected SoftReference<Map<String, EntryInfo>> myRelPathsToEntries = new SoftReference<Map<String, EntryInfo>>(null);
    protected final Object lock = new Object();
    private InputStream inputStream;

    protected final String myBasePath;

    protected static class EntryInfo {
        public EntryInfo(final String shortName, final EntryInfo parent, final boolean directory, byte[] content) {
            this.shortName = shortName;
            this.parent = parent;
            isDirectory = directory;
            this.content = content;

        }

        final boolean isDirectory;
        final byte[] content;
        protected final String shortName;
        final EntryInfo parent;
    }

    public JarHandlerBase(CoreJarFileSystem myFileSystem, String path) {
        if (inputStream == null) {
            try {
                inputStream = new VirtualJarFile(myFileSystem, path).getInputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        myBasePath = path;
    }

    @NotNull
    protected Map<String, EntryInfo> initEntries() {
        synchronized (lock) {
            Map<String, EntryInfo> map = myRelPathsToEntries.get();
            if (map == null) {
                final ZipInputStream zip = getZip();

                map = new HashMap<String, EntryInfo>();
                if (zip != null) {
                    map.put("", new EntryInfo("", null, true, new byte[0]));
                    try {
                        ZipEntry entry = zip.getNextEntry();
                        while (entry != null) {
                            final String name = entry.getName();
                            final boolean isDirectory = name.endsWith("/");

                            byte[] extra = entry.getExtra();
                            if (extra == null) {
                                byte[] cont = new byte[(int) entry.getSize()];
                                ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
                                InputStream stream = getZip();
                                if (stream != null) {
                                    int tmp;
                                    if ((tmp = stream.read(cont)) == entry.getSize()) {
                                        byteArray.write(cont, 0, tmp);
                                        extra = byteArray.toByteArray();
                                    }
                                    else {
                                        int readFromIS = tmp;
                                        if (tmp < entry.getSize()) {
                                            byteArray.write(cont, 0, tmp);
                                            while (((tmp = stream.read(cont)) != -1) && (tmp + readFromIS <= entry.getSize())/* && (tmp + readFromIS < 0xFFFF)*/) {
                                                byteArray.write(cont, 0, tmp);
                                                readFromIS += tmp;
                                            }

                                            extra = byteArray.toByteArray();
                                            if (byteArray.size() != entry.getSize()) {
                                                throw new IllegalArgumentException("Sizeof bytearray is different from entry size: " + byteArray.size() + "!=" + entry.getSize());
                                            }
                                        }
                                    }
                                }
                            }
                            getOrCreate(isDirectory ? name.substring(0, name.length() - 1) : name, isDirectory, map, extra);

                            entry = zip.getNextEntry();
                        }
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                    myRelPathsToEntries = new SoftReference<Map<String, EntryInfo>>(map);
                }
            }
            return map;
        }
    }

    public File getMirrorFile(File originalFile) {
        return originalFile;
    }

    @Nullable
    public ZipInputStream getZip() {
        ZipInputStream zip = myZipFile;
        if (zip == null) {
            if (inputStream == null) {
                throw new IllegalArgumentException("Input Stream is null: " + myBasePath);
            }
            else {
                zip = new ZipInputStream(inputStream);
            }
            myZipFile = zip;
        }
        return zip;
    }

    protected InputStream getOriginalFile() {

        return inputStream;
    }

    private static EntryInfo getOrCreate(String entryName, boolean isDirectory, Map<String, EntryInfo> map, byte[] content) {
        EntryInfo info = map.get(entryName);
        if (info == null) {
            int idx = entryName.lastIndexOf('/');
            final String parentEntryName = idx > 0 ? entryName.substring(0, idx) : "";
            String shortName = idx > 0 ? entryName.substring(idx + 1) : entryName;
            if (".".equals(shortName)) return getOrCreate(parentEntryName, true, map, content);

            info = new EntryInfo(shortName, getOrCreate(parentEntryName, true, map, content), isDirectory, content);
            map.put(entryName, info);
        }

        return info;
    }

    @NotNull
    public String[] list(@NotNull final VirtualFile file) {
        synchronized (lock) {
            EntryInfo parentEntry = getEntryInfo(file);

            Set<String> names = new HashSet<String>();
            for (EntryInfo info : getEntriesMap().values()) {
                if (info.parent == parentEntry) {
                    names.add(info.shortName);
                }
            }

            return ArrayUtil.toStringArray(names);
        }
    }

    protected EntryInfo getEntryInfo(final VirtualFile file) {
        synchronized (lock) {
            String parentPath = getRelativePath(file);
            return getEntryInfo(parentPath);
        }
    }

    public EntryInfo getEntryInfo(String parentPath) {
        return getEntriesMap().get(parentPath);
    }

    protected Map<String, EntryInfo> getEntriesMap() {
        return initEntries();
    }

    private String getRelativePath(final VirtualFile file) {
//        throw new UnsupportedOperationException(file.getPath());
        final String path = file.getPath().substring(myBasePath.length() + 1);
        return path.startsWith("/") ? path.substring(1) : path;
    }

    @Nullable
    private ZipEntry convertToEntry(VirtualFile file) {
        String path = getRelativePath(file);
        final ZipInputStream zip = getZip();
        return null;
    }

    @Nullable
    private EntryInfo convertToISEntry(VirtualFile file) {
        String path = getRelativePath(file);

        Map<String, EntryInfo> entryInfoMap = myRelPathsToEntries.get();
        if (entryInfoMap == null) {
            return null;
        }
        return entryInfoMap.get(path);
    }

    public long getLength(@NotNull final VirtualFile file) {
        synchronized (lock) {
            final ZipEntry entry = convertToEntry(file);
            return entry != null ? entry.getSize() : 0;
        }
    }

    @NotNull
    public InputStream getInputStream(@NotNull final VirtualFile file) throws IOException {
        return new ByteArrayInputStream(contentsToByteArray(file));
    }

    @NotNull
    public byte[] contentsToByteArray(@NotNull final VirtualFile file) throws IOException {
        synchronized (lock) {
            EntryInfo info = convertToISEntry(file);
            if (info == null) {
                return new byte[0];
            }
            byte[] content = info.content;
            if (content == null) {
                return new byte[0];
            }

            return content;

        }
    }

    public long getTimeStamp(@NotNull final VirtualFile file) {
        if (file.getParent() == null) return file.getTimeStamp(); // Optimization
//        if (file.getParent() == null) return getOriginalFile().lastModified(); // Optimization
        synchronized (lock) {
            final ZipEntry entry = convertToEntry(file);
            return entry != null ? entry.getTime() : -1L;
        }
    }

    public boolean isDirectory(@NotNull final VirtualFile file) {
        if (file.getParent() == null) return true; // Optimization
        synchronized (lock) {
            final String path = getRelativePath(file);
            final EntryInfo info = getEntryInfo(path);
            return info == null || info.isDirectory;
        }
    }

    public boolean exists(@NotNull final VirtualFile fileOrDirectory) {
        if (fileOrDirectory.getParent() == null) {
            // Optimization. Do not build entries if asked for jar root existence.
            return myZipFile != null;
//            return myZipFile.get() != null || getOriginalFile().exists();
        }

        return getEntryInfo(fileOrDirectory) != null;
    }
}
