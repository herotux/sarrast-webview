package com.herotux.sarrastwebview;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfRenderer;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import java.io.IOException;

public class PdfViewerActivity extends Activity {
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private ImageView image;
    private TextView pageText;
    private int page=0;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        TextView title=new TextView(this); title.setText("PDF"); title.setTextSize(18); title.setPadding(16,12,16,12);
        image=new ImageView(this); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this); scroll.addView(image,new android.widget.ScrollView.LayoutParams(-1,-2));
        LinearLayout bar=new LinearLayout(this); bar.setGravity(17);
        Button prev=new Button(this); prev.setText("قبلی");
        pageText=new TextView(this); pageText.setPadding(20,0,20,0);
        Button next=new Button(this); next.setText("بعدی");
        bar.addView(prev);bar.addView(pageText);bar.addView(next);
        root.addView(title,new LinearLayout.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);
        prev.setOnClickListener(v->{if(page>0){page--;render();}});
        next.setOnClickListener(v->{if(renderer!=null&&page<renderer.getPageCount()-1){page++;render();}});
        try{
            Uri u=getIntent().getData(); if(u==null)throw new IOException("No PDF");
            descriptor=getContentResolver().openFileDescriptor(u,"r");
            if(descriptor==null)throw new IOException("Cannot open");
            renderer=new PdfRenderer(descriptor); render();
        }catch(Exception e){pageText.setText("خطا در باز کردن PDF");}
    }

    private void render(){
        if(renderer==null)return;
        PdfRenderer.Page p=renderer.openPage(page);
        int width=Math.max(800,getResources().getDisplayMetrics().widthPixels);
        float ratio=(float)p.getHeight()/p.getWidth();
        Bitmap bmp=Bitmap.createBitmap(width,(int)(width*ratio),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bmp); canvas.drawColor(android.graphics.Color.WHITE);
        p.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); p.close();
        image.setImageBitmap(bmp); pageText.setText((page+1)+" / "+renderer.getPageCount());
    }

    @Override protected void onDestroy(){if(renderer!=null)renderer.close();if(descriptor!=null)try{descriptor.close();}catch(Exception ignored){}super.onDestroy();}
}