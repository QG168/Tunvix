package com.tunvix.app;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.*;
/** Test APK only. Java keeps this standalone provider independent of target Kotlin runtime. */
public class FixtureProvider extends ContentProvider {
 public boolean onCreate() { return true; }
 public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
  if (!"playlist.txt".equals(uri.getLastPathSegment()) || !"r".equals(mode)) throw new FileNotFoundException();
  File file = new File(getContext().getCacheDir(), "playlist.txt");
  try (FileOutputStream out = new FileOutputStream(file)) {
   out.write("测试组,#genre#\n本地频道,https://example.com/live?token=a&b=2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  } catch (IOException e) { throw new FileNotFoundException(e.toString()); }
  return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
 }
 public String getType(Uri uri) { return "text/plain"; }
 public Cursor query(Uri u,String[] p,String s,String[] a,String o) { return null; }
 public Uri insert(Uri u,ContentValues v) { return null; }
 public int delete(Uri u,String s,String[] a) { return 0; }
 public int update(Uri u,ContentValues v,String s,String[] a) { return 0; }
}
