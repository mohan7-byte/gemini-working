package com.gemini.live;

import android.app.*;import android.content.*;import android.content.pm.ServiceInfo;import android.graphics.*;import android.hardware.display.*;import android.media.*;import android.media.projection.*;import android.os.*;import android.util.*;import java.io.*;import java.nio.*;import android.util.Base64;

public class ScreenCaptureService extends Service {
    public static ScreenCaptureService instance; MediaProjection projection;
    @Override public void onCreate(){super.onCreate();instance=this;}
    @Override public int onStartCommand(Intent i,int flags,int id){
        createNotification(); if(i!=null&&i.hasExtra("data")){MediaProjectionManager m=(MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);projection=m.getMediaProjection(i.getIntExtra("resultCode",0),i.getParcelableExtra("data"));} return START_NOT_STICKY;
    }
    void createNotification(){String ch="voice_capture";NotificationManager n=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);if(Build.VERSION.SDK_INT>=26)n.createNotificationChannel(new NotificationChannel(ch,"Voice capture",NotificationManager.IMPORTANCE_LOW));Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,ch):new Notification.Builder(this);Notification x=b.setContentTitle("Voice Vision").setContentText("Screen capture ready").setSmallIcon(android.R.drawable.ic_menu_camera).build();if(Build.VERSION.SDK_INT>=29)startForeground(9,x,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);else startForeground(9,x);}
    public String capture(){if(projection==null)return null;DisplayMetrics d=getResources().getDisplayMetrics();int w=Math.min(d.widthPixels,1440),h=Math.min(d.heightPixels,2560);ImageReader r=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);VirtualDisplay v=null;Image im=null;Bitmap bmp=null;try{v=projection.createVirtualDisplay("Voice",w,h,d.densityDpi,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,r.getSurface(),null,null);for(int i=0;i<8&&im==null;i++){SystemClock.sleep(40);im=r.acquireLatestImage();}if(im==null)return null;Image.Plane p=im.getPlanes()[0];ByteBuffer buf=p.getBuffer();int stride=p.getRowStride(),pixel=p.getPixelStride();int rw=w+(stride-pixel*w)/pixel;bmp=Bitmap.createBitmap(rw,h,Bitmap.Config.ARGB_8888);bmp.copyPixelsFromBuffer(buf);Bitmap crop=Bitmap.createBitmap(bmp,0,0,w,h);ByteArrayOutputStream out=new ByteArrayOutputStream();crop.compress(Bitmap.CompressFormat.JPEG,60,out);crop.recycle();return Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);}catch(Exception e){return null;}finally{if(im!=null)im.close();if(v!=null)v.release();r.close();if(bmp!=null)bmp.recycle();}}
    @Override public void onDestroy(){if(projection!=null)projection.stop();projection=null;instance=null;super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
