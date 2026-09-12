package com.gemini.live;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.webkit.*;
import android.view.*;
import android.widget.Toast;
import java.io.*;

public class MainActivity extends Activity {
    static MainActivity instance;
    WebView web;
    MediaProjectionManager projection;
    static final int CAPTURE=202, PERMS=101;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); instance=this;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        projection=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        web=new WebView(this);
        web.setBackgroundColor(0x00000000);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); s.setAllowFileAccess(true);
        s.setAllowContentAccess(true); s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(final PermissionRequest r){
                runOnUiThread(() -> r.grant(r.getResources()));
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(web);
        requestNeededPermissions();
        web.loadUrl("file:///android_asset/index.html");
    }

    void requestNeededPermissions(){
        if(Build.VERSION.SDK_INT<23) return;
        String[] p={Manifest.permission.RECORD_AUDIO,Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,Manifest.permission.CALL_PHONE,Manifest.permission.SEND_SMS};
        java.util.ArrayList<String> need=new java.util.ArrayList<>();
        for(String x:p) if(checkSelfPermission(x)!=PackageManager.PERMISSION_GRANTED) need.add(x);
        if(!need.isEmpty()) requestPermissions(need.toArray(new String[0]),PERMS);
    }
    void requestCapture(){ if(projection!=null) startActivityForResult(projection.createScreenCaptureIntent(),CAPTURE); }
    @Override protected void onActivityResult(int r,int c,Intent d){
        super.onActivityResult(r,c,d);
        if(r==CAPTURE && c==RESULT_OK && d!=null){
            Intent i=new Intent(this,ScreenCaptureService.class).putExtra("resultCode",c).putExtra("data",d);
            if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
        }
    }
    @Override public void onTrimMemory(int level){
        super.onTrimMemory(level);
        if(web!=null && level>=TRIM_MEMORY_RUNNING_LOW) web.clearCache(false);
        if(level>=TRIM_MEMORY_COMPLETE && web!=null) web.freeMemory();
    }
    @Override protected void onNewIntent(Intent i){ super.onNewIntent(i); setIntent(i); if(web!=null) web.evaluateJavascript("typeof autoConnectOnWake==='function'&&autoConnectOnWake()",null); }
    @Override public void onBackPressed(){ moveTaskToBack(true); }
    @Override protected void onDestroy(){ if(web!=null){web.stopLoading();web.loadUrl("about:blank");web.destroy();web=null;} instance=null; super.onDestroy(); }

    public class Bridge {
        @JavascriptInterface public void minimize(){runOnUiThread(()->moveTaskToBack(true));}
        @JavascriptInterface public void closeApp(){runOnUiThread(MainActivity.this::finish);}
        @JavascriptInterface public void showTaskProgress(int step,String text){runOnUiThread(()->Toast.makeText(MainActivity.this,"⚡ ["+step+"] "+text,Toast.LENGTH_SHORT).show());}
        @JavascriptInterface public void openAccessibilitySettings(){startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
        @JavascriptInterface public void requestScreenCapturePermission(){runOnUiThread(MainActivity.this::requestCapture);}
        @JavascriptInterface public String captureScreen(){
            if(ScreenCaptureService.instance==null){runOnUiThread(MainActivity.this::requestCapture);return "Screen capture permission required.";}
            String x=ScreenCaptureService.instance.capture(); return x==null?"Capture failed.":x;
        }
        @JavascriptInterface public boolean saveRulesToFile(String json){try{
            File f=new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"rules.json");
            try(FileWriter w=new FileWriter(f)){w.write(json);} return true;
        }catch(Exception e){return false;}}
        @JavascriptInterface public void openRulesInFileManager(){startActivity(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE));}
        @JavascriptInterface public String tapElementId(int id){return svc()==null?"Accessibility Service required.":svc().tapId(id);}
        @JavascriptInterface public String longPressElementId(int id){return svc()==null?"Accessibility Service required.":svc().longId(id);}
        @JavascriptInterface public String replaceText(String s){return svc()==null?"Accessibility Service required.":svc().replace(s);}
        @JavascriptInterface public String clearText(){return replaceText("");}
        @JavascriptInterface public String typeText(String s){return svc()==null?"Accessibility Service required.":svc().type(s);}
        @JavascriptInterface public String tapElement(String s){return svc()==null?"Accessibility Service required.":svc().tapLabel(s);}
        @JavascriptInterface public String tapCoordinates(int x,int y){return svc()==null?"Accessibility Service required.":svc().tap(x,y)?"Tapped":"Tap failed";}
        @JavascriptInterface public String longPress(int x,int y){return svc()==null?"Accessibility Service required.":svc().longPress(x,y)?"Long-pressed":"Long press failed";}
        @JavascriptInterface public String swipe(int a,int b,int c,int d,int ms){return svc()==null?"Accessibility Service required.":svc().swipe(a,b,c,d,ms)?"Swiped":"Swipe failed";}
        @JavascriptInterface public String scroll(String d){return svc()==null?"Accessibility Service required.":svc().scroll(d)?"Scrolled":"Scroll failed";}
        @JavascriptInterface public String readScreenText(){return svc()==null?"Accessibility Service required.":svc().read();}
        @JavascriptInterface public String navigateSystem(String a){return svc()==null?"Accessibility Service required.":svc().navigate(a);}
        @JavascriptInterface public String openApp(String name){
            android.content.pm.PackageManager pm=getPackageManager();
            for(android.content.pm.ApplicationInfo a:pm.getInstalledApplications(0)){
                String label=pm.getApplicationLabel(a).toString();
                if(label.toLowerCase().contains(name.toLowerCase())){Intent i=pm.getLaunchIntentForPackage(a.packageName);if(i!=null){startActivity(i);return "Opened "+label;}}
            } return "App not found: "+name;
        }
        @JavascriptInterface public String toggleFlashlight(boolean on){try{android.hardware.camera2.CameraManager c=(android.hardware.camera2.CameraManager)getSystemService(CAMERA_SERVICE);c.setTorchMode(c.getCameraIdList()[0],on);return "Flashlight "+(on?"ON":"OFF");}catch(Exception e){return e.toString();}}
        @JavascriptInterface public String setVolume(int p){android.media.AudioManager a=(android.media.AudioManager)getSystemService(AUDIO_SERVICE);int m=a.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);a.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,Math.round(m*Math.max(0,Math.min(100,p))/100f),android.media.AudioManager.FLAG_SHOW_UI);return "Volume set";}
        @JavascriptInterface public String makePhoneCall(String n){try{startActivity(new Intent(Intent.ACTION_CALL,Uri.parse("tel:"+n)));return "Calling";}catch(Exception e){return e.toString();}}
        @JavascriptInterface public String sendSms(String n,String m){try{android.telephony.SmsManager.getDefault().sendTextMessage(n,null,m,null,null);return "SMS sent";}catch(Exception e){return e.toString();}}
        @JavascriptInterface public String searchContacts(String q){android.database.Cursor c=null;try{c=getContentResolver().query(android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,new String[]{"display_name","data1"},"display_name LIKE ?",new String[]{"%"+q+"%"},null);StringBuilder b=new StringBuilder();int n=0;while(c!=null&&c.moveToNext()&&n++<5)b.append(c.getString(0)).append(": ").append(c.getString(1)).append('\n');return b.length()==0?"No contacts found.":b.toString();}finally{if(c!=null)c.close();}}
        @JavascriptInterface public String searchYouTube(String q){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.youtube.com/results?search_query="+Uri.encode(q))));return "Opened YouTube";}
        @JavascriptInterface public String searchWeb(String q){startActivity(new Intent(Intent.ACTION_WEB_SEARCH).putExtra("query",q));return "Searching";}
        @JavascriptInterface public String openWhatsAppChat(String n,String m){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/"+n.replaceAll("[^0-9]","")+"?text="+Uri.encode(m))));return "Opened WhatsApp";}
        @JavascriptInterface public String createNote(String text){startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,text),"Create Note"));return "Opened note composer";}
        VolumeTriggerService svc(){return VolumeTriggerService.instance;}
    }
}
