package com.decibelmeter.app;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.codelibs.jcifs.smb.CIFSContext;
import org.codelibs.jcifs.smb.CIFSException;
import org.codelibs.jcifs.smb.SmbRandomAccess;
import org.codelibs.jcifs.smb.SmbResource;
import org.codelibs.jcifs.smb.config.PropertyConfiguration;
import org.codelibs.jcifs.smb.context.BaseContext;
import org.codelibs.jcifs.smb.impl.NtlmPasswordAuthenticator;
import org.codelibs.jcifs.smb.impl.SmbFile;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SMB 客户端层：把 jcifs-ng 的资源树包装成「按路径说话」的接口，界面层不再持有 SmbFile。
 *
 * 路径约定和 UNC 一致：目录以 {@code /} 结尾，文件不带；空字符串表示「共享列表」那一层。
 * 资源一律从根按段构造（{@code new SmbFile(父, 名称)}），不拼 URL 字符串——jcifs 会自己做
 * 相对路径编码，中文名和带空格、井号的文件名因此不会被拼错。
 *
 * 传输都带 {@link Progress} 回调，其中 {@code isCancelled()} 让长拷贝能响应用户的中止；所有
 * 方法都必须在工作线程调用，这里没有任何 Android 主线程相关的保护。
 */
public final class SmbClient {

    /**
     * 一次列表最多返回多少项。共享里几万个条目的目录真实存在（照片备份、固件包），
     * 全部塞进 ListView 会让界面卡死，也更费内存——截断并说明比默默卡住诚实。
     */
    public static final int LIST_LIMIT = 2000;

    /**
     * 传输缓冲大小，同时用于流内部缓冲与拷贝循环。
     *
     * 原来 64 KB：一次 read 就是一次 SMB 往返，Wi-Fi 6 上实测上传只有 8.3 MB/s、下载 15 MB/s，
     * 而链路是 1200 Mbps——差的一个数量级全耗在「每 64 KB 等一个 RTT」上。抬到 1 MB 后同一个
     * 文件的上传/下载都翻了几倍（见 CHANGELOG）。1 MB 而不是 8 MB：Windows 的 SMB2 单次读
     * 最多许可 8 MB，但那是按会话额度分片给的，1 MB 已经能把 RTT 摊薄到可忽略，又不至于让
     * 两条并发传输各占一大块内存。
     */
    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final int ATTR_DIRECTORY = 0x10;
    private static final int ATTR_HIDDEN = 0x02;

    public interface Progress {
        boolean isCancelled();

        /** @param total 未知时传 -1 */
        void onProgress(long done, long total);
    }

    /** 列表里的一项。size/modified 取不到时分别是 -1 与 0，隐藏共享与无权限项本来就没有元数据。 */
    public static final class Item {
        public final String name;
        public final boolean directory;
        public final long size;
        public final long modified;
        public final boolean hidden;

        Item(String name, boolean directory, long size, long modified, boolean hidden) {
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.modified = modified;
            this.hidden = hidden;
        }
    }

    public static final class Listing {
        public final List<Item> items;
        public final boolean truncated;
        /** 所在共享的剩余空间，取不到是 -1。 */
        public final long free;

        Listing(List<Item> items, boolean truncated, long free) {
            this.items = items;
            this.truncated = truncated;
            this.free = free;
        }
    }

    private final String host;
    private final CIFSContext context;
    private final SmbFile root;

    /**
     * @throws GeneralSecurityException BouncyCastle 注册或 Keystore 相关失败
     */
    public SmbClient(String host, CredentialStore.Credential credential, SmbSettings settings)
            throws GeneralSecurityException, CIFSException, MalformedURLException {
        this.host = host;
        // jcifs-ng 的 NTLMv2/SMB3 原语走 BouncyCastle，Android 没有预置这个 Provider
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        CIFSContext base = new BaseContext(new PropertyConfiguration(settings.toProperties()));
        this.context = credential.isAnonymous() ? base.withAnonymousCredentials()
                : base.withCredentials(new NtlmPasswordAuthenticator(
                        credential.domain, credential.user, credential.password));
        this.root = new SmbFile("smb://" + host + "/", context);
    }

    public void close() {
        try {
            context.close();
        } catch (CIFSException e) {
            // 连接池已经关了，没有可清理的
        }
    }

    /**
     * 列出目录（{@code path} 为空则列共享）。
     *
     * 元数据逐项读、单项失败不影响整张列表：隐藏共享常常「存在但没权限」，这时候能列出名字
     * 也比整页报错有用。
     */
    public Listing list(String path, boolean includeHidden) throws IOException {
        return list(path, includeHidden, true);
    }

    /**
     * @param wantFree 顺带问一次剩余空间。搜索时逐目录扫，每个目录多一次 RPC 就是几百次浪费
     */
    private Listing list(String path, boolean includeHidden, boolean wantFree) throws IOException {
        SmbFile dir = resolve(path, true);
        // 共享列表这一层的每一项都是共享，而共享根问不出属性（getAttributes 返回 0，
        // 没有 ATTR_DIRECTORY 位），照实读就会被当成文件——点它不进去而是弹菜单。
        // 真机实测（连 Windows 的 ADMIN$/E$）确认了这一点，所以这一层直接按目录处理。
        boolean atShareRoot = path.isEmpty();
        List<Item> items = new ArrayList<>();
        boolean truncated = false;
        SmbFile[] children = dir.listFiles();
        if (children != null) {
            for (SmbFile child : children) {
                if (items.size() >= LIST_LIMIT) {
                    truncated = true;
                    break;
                }
                Item item = readItem(child);
                if (item == null) {
                    continue;
                }
                if (atShareRoot && !item.directory) {
                    item = new Item(item.name, true, item.size, item.modified, item.hidden);
                }
                if (includeHidden || !item.hidden) {
                    items.add(item);
                }
            }
        }
        long free = -1;
        if (wantFree) {
            try {
                free = dir.getDiskFreeSpace();
            } catch (Exception e) {
                // 不是共享根、或者账号问不出容量，都是正常情况
            }
        }
        if (free <= 0) {
            // 根层问出来的是 0 而不是「不知道」，显示「剩余 0 B」会让人以为盘满了
            free = -1;
        }
        return new Listing(items, truncated, free);
    }

    /** 读一个条目的信息；连名字都拿不到就返回 null，让它从列表里消失而不是拖垮整页。 */
    private static Item readItem(SmbFile child) {
        String raw = child.getName();
        String name = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        if (name.isEmpty()) {
            return null;
        }
        boolean directory = false;
        boolean hidden = false;
        try {
            int attrs = child.getAttributes();
            directory = (attrs & ATTR_DIRECTORY) != 0;
            hidden = (attrs & ATTR_HIDDEN) != 0;
        } catch (Exception e) {
            try {
                directory = child.isDirectory();
            } catch (Exception ignored) {
                // 属性完全问不出来：当文件处理，大小与时间留未知
            }
        }
        long size = -1;
        long modified = 0;
        try {
            if (!directory) {
                size = child.length();
            }
            modified = child.lastModified();
        } catch (Exception e) {
            // 保留 -1 / 0，界面显示「未知」
        }
        return new Item(name, directory, size, modified, hidden);
    }

    /**
     * 取一个资源句柄给随机读取层（{@link SmbRange}）用。
     *
     * 看缩略图、播视频、翻 PDF 都要按偏移读，而 {@link #download} 只会整条顺序拉。这里把已经
     * 建好的 {@code CIFSContext} 复用出去——每打开一个文件就重新登录一次的话，翻相册会变成
     * 「每张照片一次 NTLM 握手」，慢到不能用。返回的对象自己不留连接，由调用方负责关闭。
     */
    public SmbResource resource(String path, boolean directory) throws IOException {
        return resolve(path, directory);
    }

    /** 当前主机地址，缩略图 URI 与回环流地址都要用它当身份的一部分。 */
    public String host() {
        return host;
    }

    /**
     * 下载到一个输出流。
     *
     * 参数是流而不是文件，是因为「另存到手机某个位置」要写的是 SAF 给的 content URI，
     * 本地路径在那种情况下根本不存在；调用方自己决定 FileOutputStream 还是
     * {@code getContentResolver().openOutputStream(uri)}。
     */
    public void download(String path, OutputStream out, Progress progress) throws IOException {
        download(path, 0, out, progress);
    }

    /**
     * 从第 {@code offset} 字节开始下载，用于断点续传。
     *
     * offset > 0 时改用随机读句柄直接定位：顺序流想跳过前面的字节只能读完再丢，
     * 续传一个 1.7 GB 的文件等于先把前面那些白读一遍。
     */
    public void download(String path, long offset, OutputStream out, Progress progress)
            throws IOException {
        SmbFile file = resolve(path, false);
        // 大小用已经打开的这个资源问，别再 resolve 一次：那是一次多余的往返 + 一个多余资源
        long total = -1;
        try {
            total = file.length();
        } catch (Exception e) {
            // 问不出大小就按未知处理，进度条切成「已传字节」模式
        }
        // 不传显式缓冲：SMB2 下单次读大小取的是服务端协商值（SmbFileInputStream.init 里就是
        // th.getReceiveBufferSize()），传个更大的数反而让 READ 超限，服务器直接回
        // STATUS_INVALID_PARAMETER（实测踩过）。能调的只有套接字缓冲，见 SmbSettings.toProperties
        if (offset <= 0) {
            InputStream in = file.openInputStream();
            try {
                copy((buffer, length) -> in.read(buffer, 0, length), out, 0, total, progress);
            } finally {
                closeQuietly(in);
                closeQuietly(out);
            }
            return;
        }
        SmbRandomAccess access = file.openRandomAccess("r");
        try {
            access.seek(offset);
            copy((buffer, length) -> access.read(buffer, 0, length), out, offset, total, progress);
        } finally {
            closeQuietly(access);
            closeQuietly(out);
        }
    }

    /** 远端文件大小，取不到返回 -1（进度条据此切成「已传字节」模式）。 */
    public long size(String path) {
        try {
            return resolve(path, false).length();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 远端修改时间，取不到返回 0。 */
    public long lastModified(String path) {
        try {
            return resolve(path, false).lastModified();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 上传一个 SAF 选中的文件到 {@code dirPath}（必须以 '/' 结尾）。 */
    public void upload(Context context, Uri source, String dirPath, Progress progress)
            throws IOException {
        upload(context, source, displayName(context, source), sizeOf(context, source),
                dirPath, progress);
    }

    /**
     * 名字和大小已经知道的那次上传：省掉两次 ContentResolver 查询。
     *
     * 相册备份一次要推几千个文件，而每个文件的 DISPLAY_NAME 与 SIZE 在列 MediaStore 那一趟
     * 就已经拿到了；再按 URI 各查一遍就是上万次白跑的进程间调用（查询本身比小文件的传输还慢）。
     */
    public void upload(Context context, Uri source, String name, long total, String dirPath,
            Progress progress) throws IOException {
        String safe = FileUtil.sanitize(name);
        // 同名就自动避让：直接覆盖远端文件是不可逆的，而用户很可能只是忘了目录里已经有一个
        SmbFile target = resolve(uniqueIn(dirPath, safe), false);
        InputStream in = context.getContentResolver().openInputStream(source);
        if (in == null) {
            throw new IOException("无法读取所选文件");
        }
        // 写侧不能传大缓冲：实测 1 MB 会被服务端以 STATUS_INVALID_PARAMETER 拒掉
        // （这个会话的单次写上限就是 64 KB）。上传的进一步提速只能靠并行写，
        // 而 ContentResolver 给的是顺序流，得自己切块分发，留待以后
        OutputStream out = target.openOutputStream(false);
        try {
            copy((buffer, length) -> in.read(buffer, 0, length), out, 0, total, progress);
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** 找到共享上没被占用的名字：`照片.jpg` → `照片 (2).jpg`，最多试 999 次。 */
    private String uniqueIn(String dirPath, String name) throws IOException {
        String candidate = dirPath + name;
        if (!resolve(candidate, false).exists()) {
            return candidate;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            candidate = dirPath + base + " (" + i + ")" + ext;
            if (!resolve(candidate, false).exists()) {
                return candidate;
            }
        }
        return dirPath + base + "-" + System.currentTimeMillis() + ext;
    }

    public void createFolder(String dirPath) throws IOException {
        resolve(dirPath, true).mkdir();
    }

    /**
     * 逐级建出 {@code dirPath}（以 '/' 结尾），已存在的跳过。
     *
     * 解压带目录结构的压缩包要用：一个包里可能有几十层，逐层问「在不在」会变成几十次往返，
     * 但直接建又要把「目录已存在」这种正常情况从「没权限」里分出来。所以先 exists 再 mkdir，
     * 真正的错误原样抛出。
     */
    public void mkdirs(String dirPath) throws IOException {
        StringBuilder built = new StringBuilder();
        for (String segment : dirPath.split("/")) {
            if (segment.isEmpty()) {
                continue;
            }
            built.append(segment).append('/');
            SmbFile file = resolve(built.toString(), true);
            if (!file.exists()) {
                file.mkdir();
            }
        }
    }

    /**
     * 把一个流写成 {@code dirPath} 下的文件（重名自动避让），返回真正落下的名字。
     *
     * 给解压用：条目名是包里的名字，不能当成已经避让过。{@code total} 未知传 -1。
     * 只关输出流——{@code input} 归调用方（解压器的那个流）负责。
     */
    public String writeFile(String dirPath, String name, InputStream input, long total,
            Progress progress) throws IOException {
        String full = uniqueIn(dirPath, name);
        SmbFile target = resolve(full, false);
        OutputStream out = target.openOutputStream(false);
        boolean complete = false;
        try {
            copy((buffer, length) -> input.read(buffer, 0, length), out, 0, total, progress);
            complete = true;
        } finally {
            closeQuietly(out);
            if (!complete) {
                // 半截文件比没有文件更坏：它顶着正常的名字，下次按名字判断「已经有了」就会被跳过
                // （解压失败留在共享上的那个 0 字节 hello.txt 就是这么来的）
                try {
                    target.delete();
                } catch (IOException ignore) {
                    // 删不掉也只是多留一个坏文件，真正的错误还是要报出去
                }
            }
        }
        return full.substring(dirPath.length());
    }

    /**
     * 把一个文件或目录复制到 {@code targetDir}（以 '/' 结尾），返回落在目标目录里的名字。
     *
     * 走 jcifs 的 {@code copyTo}：SMB2 上它会用 FSCTL_SRV_COPYCHUNK 让服务端自己搬字节，
     * 数据不绕手机一圈；服务端不支持时才退回「读出来再写回去」。目录会递归复制。
     *
     * **重名一律避让**，绝不覆盖：服务端复制是直接覆盖写的，撞上同名文件就是不可逆的数据丢失。
     */
    public String copy(String sourcePath, String targetDir) throws IOException {
        boolean directory = sourcePath.endsWith("/");
        SmbFile source = resolve(sourcePath, directory);
        String target = uniqueIn(targetDir, baseName(sourcePath));
        source.copyTo(resolve(target, directory));
        return target.substring(targetDir.length());
    }

    /**
     * 把文件或目录移动到 {@code targetDir}，返回新名字。
     *
     * 同一个共享里就是服务端改名（瞬间完成，数据不动）。跨共享/跨卷时服务端可能拒绝改名，
     * 这时退回「复制 + 删除」——慢，但至少能做；失败会原样抛出，让用户看到真正的错误。
     */
    public String move(String sourcePath, String targetDir) throws IOException {
        boolean directory = sourcePath.endsWith("/");
        SmbFile source = resolve(sourcePath, directory);
        String target = uniqueIn(targetDir, baseName(sourcePath));
        SmbFile destination = resolve(target, directory);
        try {
            source.renameTo(destination, false);
        } catch (IOException e) {
            source.copyTo(destination);
            source.delete();
        }
        return target.substring(targetDir.length());
    }

    /** 搜索命中的一项。 */
    public interface SearchVisitor {
        /** @return false 表示停止搜索 */
        boolean onHit(String path, String name, boolean directory, long size, long modified);
    }

    /**
     * 在 {@code basePath} 及其子目录里按名字搜（不区分大小写），返回扫过的目录数。
     *
     * SMB 没有搜索接口，只能逐目录列，所以设了三道闸：最多 [maxDirs] 个目录、[maxEntries] 个
     * 条目，命中数由调用方在 [SearchVisitor] 里自己数（返回 false 即停）。某一个子目录没权限
     * 就跳过它继续——一个目录失败不该让整次搜索白跑。
     */
    public int search(String basePath, String needle, int maxDirs, int maxEntries,
            SearchVisitor visitor) {
        String lowered = needle.toLowerCase(Locale.US);
        int visited = 0;
        int scanned = 0;
        ArrayList<String> queue = new ArrayList<>();
        queue.add(basePath);
        for (int head = 0; head < queue.size() && visited < maxDirs && scanned < maxEntries; head++) {
            String dirPath = queue.get(head);
            visited++;
            List<Item> items;
            try {
                // 搜索不需要剩余空间：逐目录问一次就多几百次 RPC
                items = list(dirPath, false, false).items;
            } catch (Exception e) {
                continue;
            }
            for (Item item : items) {
                if (scanned++ >= maxEntries) {
                    break;
                }
                String childPath = child(dirPath, item.name, item.directory);
                if (item.name.toLowerCase(Locale.US).contains(lowered)
                        && !visitor.onHit(childPath, item.name, item.directory, item.size,
                        item.modified)) {
                    return visited;
                }
                if (item.directory) {
                    queue.add(childPath);
                }
            }
        }
        return visited;
    }

    /** 路径最后一段（目录的尾斜杠不算）。 */
    private static String baseName(String path) {        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    public void delete(String path) throws IOException {
        resolve(path, path.endsWith("/")).delete();
    }

    /** 重命名：目标固定在同一个父目录里，只换名字，跨目录移动不在这个功能范围内。 */
    public void rename(String from, String newName) throws IOException {
        String parent = parentOf(from);
        boolean directory = from.endsWith("/");
        String cleaned = directory ? newName + "/" : newName;
        SmbFile source = resolve(from, directory);
        SmbFile destination = resolve(parent + cleaned, directory);
        source.renameTo(destination, false);
    }

    /** 转成 UNC 写法给界面显示与复制：{@code \\主机\共享\目录\文件}。 */
    public String unc(String path) {
        StringBuilder builder = new StringBuilder("\\\\").append(host);
        for (String segment : path.split("/")) {
            if (!segment.isEmpty()) {
                builder.append('\\').append(segment);
            }
        }
        return builder.toString();
    }

    /** 父目录路径（始终以 '/' 结尾）。传入 {@code "Share/Docs/"} 得到 {@code "Share/"}。 */
    public static String parentOf(String path) {
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? "" : trimmed.substring(0, slash + 1);
    }

    /** 在目录路径后面接一段名字，保证分隔符只有一个。 */
    public static String child(String dirPath, String name, boolean directory) {
        String base = dirPath.endsWith("/") ? dirPath : dirPath + "/";
        return base + name + (directory ? "/" : "");
    }

    private SmbFile resolve(String path, boolean directory) throws IOException {
        SmbFile current = root;
        String[] parts = path.split("/");
        List<String> segments = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                segments.add(part);
            }
        }
        for (int i = 0; i < segments.size(); i++) {
            boolean last = i == segments.size() - 1;
            String name = segments.get(i);
            // 目录 URL 必须带尾斜杠；jcifs 会把 context 一律当目录来解析相对名，
            // 但结果资源是目录还是文件仍然取决于 URL 自己有没有那个斜杠
            try {
                current = new SmbFile(current, directory || !last ? name + "/" : name);
            } catch (UnknownHostException e) {
                // 声明上会抛，实际这一步还没有网络访问；真发生就是主机名解析不出来
                throw new IOException("无法解析主机 " + host + "：" + describe(e), e);
            }
        }
        return current;
    }

    /** 从任意数据源按「填缓冲区」的方式读字节：顺序流和随机读写都能接进来。 */
    private interface Reader {
        int read(byte[] buffer, int length) throws IOException;
    }

    private static void copy(Reader reader, OutputStream out, long start, long total,
            Progress progress) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long done = start;
        int read;
        progress.onProgress(start, total);
        while ((read = reader.read(buffer, buffer.length)) > 0) {
            if (progress.isCancelled()) {
                throw new IOException("已取消");
            }
            out.write(buffer, 0, read);
            done += read;
            progress.onProgress(done, total);
        }
        out.flush();
    }

    private static String displayName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception e) {
            // 有些 provider 不支持查询列名，退回 URI 的最后一段
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String tail = uri.getLastPathSegment();
        return tail == null || tail.isEmpty() ? "upload.bin" : tail;
    }

    private static long sizeOf(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.SIZE}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                long size = cursor.getLong(0);
                return size > 0 ? size : -1;
            }
        } catch (Exception e) {
            // 拿不到大小就按未知处理，进度条显示已传字节
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return -1;
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            // 已经出过错的流再关一次失败没有额外信息
        }
    }

    /** jcifs 的随机读句柄只实现了 AutoCloseable（没有 Closeable），所以单独来一个重载。 */
    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            // 同上
        }
    }

    /**
     * 把 jcifs 的英文协议错误归到几类常见原因上，原文一起带出来方便对照日志。
     *
     * 「权限不足」和「账号不对」在服务端常常是同一个 NT_STATUS_ACCESS_DENIED，所以文案写成
     * 两种可能都成立，并给出下一步动作（换账号 / 开 SMB1 兼容），而不是让用户对着原文猜。
     */
    public static String describeError(Context context, Exception e) {
        String message = describe(e).toLowerCase(Locale.US);
        if (message.contains("access is denied") || message.contains("access_denied")
                || message.contains("no more available connections")) {
            return context.getString(R.string.smb_error_denied, describe(e));
        }
        if (message.contains("logon") || message.contains("account")
                || message.contains("password") || message.contains("bad workstation")) {
            return context.getString(R.string.smb_error_logon, describe(e));
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return context.getString(R.string.smb_error_timeout, describe(e));
        }
        if (message.contains("protocol") || message.contains("dialect")
                || message.contains("smb1") || message.contains("not supported")) {
            return context.getString(R.string.smb_error_dialect, describe(e));
        }
        return context.getString(R.string.smb_error_format, describe(e));
    }

    public static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.toString() : message;
    }
}
