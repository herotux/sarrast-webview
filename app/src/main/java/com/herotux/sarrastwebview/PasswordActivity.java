package com.herotux.sarrastwebview;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.security.MessageDigest;
import java.security.SecureRandom;

public class PasswordActivity extends Activity {
    private static final String PREFS="app_security";
    private static final String HASH="password_hash";
    private static final String SALT="password_salt";

    private String hash(String password, byte[] salt) throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");
        md.update(salt);
        return android.util.Base64.encodeToString(md.digest(password.getBytes("UTF-8")), android.util.Base64.NO_WRAP);
    }

    private byte[] getSalt() {
        String s=getSharedPreferences(PREFS,MODE_PRIVATE).getString(SALT,null);
        return s==null ? null : android.util.Base64.decode(s,android.util.Base64.NO_WRAP);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER);
        int p=(int)(32*getResources().getDisplayMetrics().density);
        root.setPadding(p,p,p,p);
        TextView title=new TextView(this); title.setText("Sarrast"); title.setTextSize(28); title.setGravity(17);
        EditText input=new EditText(this); input.setSingleLine(true); input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD); input.setHint("رمز ورود");
        Button button=new Button(this); button.setText("ورود");
        root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        root.addView(input,new LinearLayout.LayoutParams(-1,-2));
        root.addView(button,new LinearLayout.LayoutParams(-1,-2));
        setContentView(root);

        final android.content.SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        if(!prefs.contains(HASH)){
            title.setText("ایجاد رمز ورود");
            button.setText("ذخیره رمز");
        }
        button.setOnClickListener(v->{
            String pass=input.getText().toString();
            if(pass.length()<4){input.setError("حداقل ۴ کاراکتر");return;}
            try{
                byte[] salt=getSalt();
                if(salt==null){
                    salt=new byte[16]; new SecureRandom().nextBytes(salt);
                    prefs.edit().putString(SALT,android.util.Base64.encodeToString(salt,android.util.Base64.NO_WRAP)).apply();
                }
                String h=hash(pass,salt);
                if(!prefs.contains(HASH)){
                    prefs.edit().putString(HASH,h).apply();
                    openApp();
                } else if(h.equals(prefs.getString(HASH,""))){
                    openApp();
                } else {
                    input.setError("رمز اشتباه است");
                }
            }catch(Exception e){Toast.makeText(this,"خطا در بررسی رمز",Toast.LENGTH_SHORT).show();}
        });
    }

    private void openApp(){
        startActivity(new Intent(this,MainActivity.class));
        finish();
    }
}