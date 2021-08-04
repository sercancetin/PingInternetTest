package com.interra.pinginternettest;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String LOG_TAG = "MultiPing";
    private TextView myTextView = null;
    private EditText myEditText = null;
    private ImageButton refreshbutton;

    private class PingerItem {
        String hostname = null;
        InetAddress ia = null;
        long result_80 = MAXTIME; // connect 80
        long result_av = MAXTIME; // isAvailable
    }

    public class NameResolver implements Runnable {
        private String hostname;
        public NameResolver(String hostname) {
            this.hostname = hostname;
        }

        public void run() {
            Log.v("multping","NameResolver "+hostname);
            InetAddress ia;
            try {
                ia = InetAddress.getByName(hostname);

                for(int i=0; i<items.size(); i++)
                {
                    PingerItem pi = items.get(i);
                    if(pi.hostname.equals(hostname)) {
                        Log.v("multping","NameResolver "+hostname + " resolved:" + ia);
                        pi.ia = ia;
                        items.set(i,pi);
                    }
                }
            } catch (UnknownHostException e) {
                //
            } catch (Exception e) {
                //
            }
        }
    }

    public class Pinger80 implements Runnable {
        private InetAddress ia;
        public Pinger80(InetAddress ia) {
            this.ia = ia;
        }

        public void run() {
            Log.v("multiping", "Pinger80 " + ia + "start" );
            long t1 = System.nanoTime();
            // Try port 80
            try {
                long dt = TIMEOUT;
                try {
                    Socket socket = new Socket(ia, 80);
                    long t2 = System.nanoTime();
                    dt = (t2-t1)/1000000;
                    socket.close();
                } catch (IOException e) {
                    Log.v("multiping","Pinger80 " + e.toString());
                }

                for(int i=0; i<items.size(); i++)
                {
                    PingerItem pi = items.get(i);
                    if(pi.ia.equals(ia))
                    {
                        pi.result_80 = dt;
                        items.set(i,pi);
                        Log.v("multiping", "Pinger80 "+pi.hostname+" "+dt);
                    }
                }

            } catch (Exception e) {
                Log.v("multiping","Pinger80 " + e.toString());
            }
            Log.v("multiping", "Pinger80 " + ia + "end" );
        }
    }

    public class PingerAv implements Runnable {
        private InetAddress ia;
        public PingerAv(InetAddress ia) {
            this.ia = ia;
        }

        public void run() {
            Log.v("multiping", "PingerAv " + ia + "start" );
            // Try port 7
            for(int i=0; i<items.size(); i++)
            {
                try {
                    PingerItem pi = items.get(i);
                    if(pi.ia.equals(ia))
                    {
                        long t1 = System.nanoTime();
                        try {
                            if(pi.ia.isReachable(TIMEOUT))
                            {
                                long t2 = System.nanoTime();
                                long dt = (t2-t1)/1000000;

                                pi = items.get(i);
                                pi.result_av = dt;
                                items.set(i, pi);
                                Log.v("multiping", "PingerAv "+pi.hostname+" "+ pi.result_av);
                            }
                            else
                            {
                                pi = items.get(i);
                                pi.result_av = TIMEOUT;
                                items.set(i, pi);
                                Log.v("multiping", "PingerAv TIMEOUT "+pi.hostname+" "+ pi.result_av);
                            }
                        } catch (IOException e) {
                            pi = items.get(i);
                            pi.result_av = TIMEOUT;
                            items.set(i, pi);
                            Log.v("multiping","PingerAv " + e.toString());
                        }
                    }
                } catch (Exception e) {
                    Log.v("multiping","PingerAv " + e.toString());
                }
            }
            Log.v("multiping", "PingerAv " + ia + "end" );
        }
    }

    TextView selection;

    private final static String SAVEFILE="hosts";
    final static int MAXTIME = 100000;
    final static int TIMEOUT = 3000;
    final static long PERIOD = 5000;
    final static long UPDATE = 1000;
    final static int MAXHOSTS = 12;
    Activity content=null;
    int m_position = 0;

    Thread m_background=null;
    boolean isRunning=false;

    final ArrayList<PingerItem> items = new ArrayList<PingerItem>();
    PingItemAdapter pia=null;

    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            String localIp = getLocalIpAddress();
            if (localIp == null) localIp = "unknown";
            myTextView.setText("Local IP : " + localIp);

            pia.notifyDataSetChanged();
        }
    };

    public String getLocalIpAddress() {
        String sLocalIpAddress="";
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress()) {
                        String sIpAddress = inetAddress.getHostAddress().toString();
                        if(sIpAddress.startsWith("fe80:")) {
                            // Ignore IPv6 Link local address
                        } else if(sIpAddress.startsWith("::127.") || sIpAddress.startsWith("::172.")) {
                            // Ignore local loopback address
                        } else {
                            sLocalIpAddress = sLocalIpAddress + " " + sIpAddress;
                        }
                    }
                }
            }
            return sLocalIpAddress;
        } catch (SocketException ex) {
            Log.e(LOG_TAG, ex.toString());
        }
        return null;
    }

    class PingItemAdapter extends ArrayAdapter<PingerItem> {
        Activity context;

        PingItemAdapter(Activity context) {
            super(context, R.layout.pingitem, items);

            this.context=context;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            View row=convertView;
            ViewWrapper wrapper=null;

            if(row==null) {
                LayoutInflater inflater = context.getLayoutInflater();
                row=inflater.inflate(R.layout.pingitem, null);
                wrapper=new ViewWrapper(row);
                row.setTag(wrapper);
            }
            else {
                wrapper=(ViewWrapper)row.getTag();
            }

            int textcolor;
            String textresult;
            PingerItem pi = items.get(position);

            long result;
            if(pi.result_80>pi.result_av)
                result=pi.result_av;
            else
                result=pi.result_80;

            if(result>=MAXTIME) {
                textcolor = Color.GRAY;
                textresult = "wait..";
            } else if (result>=TIMEOUT) {
                textcolor = Color.RED;
                textresult = "timeout";
            } else {
                textcolor = Color.BLACK;
                textresult = result + "ms";
            }

            String sIp;
            if(pi.ia==null)
                sIp = "0.0.0.0";
            else
                sIp = pi.ia.toString().replaceFirst(".*/", "");
            wrapper.getViewHostIp().setTextColor(textcolor);
            wrapper.getViewHostIp().setText(pi.hostname + "\n" + sIp);
            wrapper.getViewDelay().setTextColor(textcolor);
            wrapper.getViewDelay().setText(textresult);
            return row;
        }
    }

    private boolean AddHostName(String hostname) {
        PingerItem pi = new PingerItem();
        pi.hostname = hostname;
        items.add(0,pi);
        pia.notifyDataSetChanged();

        Thread t = new Thread(new NameResolver(hostname));
        t.start();

        if(items.size()>=MAXHOSTS) {
            myEditText.setText("Max number of host");
            myEditText.setEnabled(false);
        } else {
            myEditText.setText("");
        }

        return true;
    }
    /*
     * Load file content to String
     */
    public static String loadFileAsString(String filePath) throws java.io.IOException{
        StringBuffer fileData = new StringBuffer(1000);
        BufferedReader reader = new BufferedReader(new FileReader(filePath));
        char[] buf = new char[1024];
        int numRead=0;
        while((numRead=reader.read(buf)) != -1){
            String readData = String.valueOf(buf, 0, numRead);
            fileData.append(readData);
        }
        reader.close();
        return fileData.toString();
    }

    /*
     * Get the STB MacAddress //Ethernet Mac Adress
     */
    public String getMacAddress(){
        try {
            return loadFileAsString("/sys/class/net/eth0/address")
                    .toUpperCase().substring(0, 17);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
    public String wifiOrEthernetMacAdress(){
        if(getMacAddress()!=null){
            return getMacAddress();
        }else{
            return getMacAddr();
        }
    }
    //getting mac address from mobile and wifi
    private String getMacAddr() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nif : all) {
                if (!nif.getName().equalsIgnoreCase("wlan0")) continue;

                byte[] macBytes = nif.getHardwareAddress();
                if (macBytes == null) {
                    return "";
                }

                StringBuilder res1 = new StringBuilder();
                for (byte b : macBytes) {
                    res1.append(String.format("%02X:",b));
                }

                if (res1.length() > 0) {
                    res1.deleteCharAt(res1.length() - 1);
                }
                return res1.toString();
            }
        } catch (Exception ex) {
        }
        return "";
    }
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        content=this;
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        fullScreenActivity();
        myEditText = (EditText)findViewById(R.id.myEditText);
        myTextView = (TextView)findViewById(R.id.myTextView);
        TextView txt_mac = findViewById(R.id.txt_mac);
        refreshbutton = findViewById(R.id.refreshbutton);
        final ListView myListView = (ListView)findViewById(R.id.myListView);
        final Button myButton = (Button) findViewById(R.id.myButton);

        String localIp = getLocalIpAddress();
        if (localIp == null) localIp = "unknown";
        myTextView.setText("Local IP : " + localIp);


        pia = new PingItemAdapter(this);
        myListView.setAdapter(pia);
        myListView.setOnItemClickListener(new AdapterView.OnItemClickListener(){
            public void onItemClick(AdapterView av, View v, int position, long arg) {

                m_position = position;

                new AlertDialog.Builder(content)
                        .setTitle("Confirm")
                        .setMessage("Delete '" + items.get(m_position).hostname + "'")
                        .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                items.remove(m_position);
                                pia.notifyDataSetChanged();
                                if(items.size()<MAXHOSTS) {
                                    myEditText.setText("");
                                    myEditText.setEnabled(true);
                                }
                            }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                //
                            }
                        })
                        .setCancelable(true)
                        .show();

            }
        });

        myEditText.setOnKeyListener(new View.OnKeyListener(){
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if(event.getAction()==KeyEvent.ACTION_DOWN)
                {
                    if(keyCode==KeyEvent.KEYCODE_DPAD_CENTER || keyCode==KeyEvent.KEYCODE_ENTER) {
                        if(myEditText.getText().toString().length()>3){
                            String hostname = myEditText.getText().toString();
                            hostname = hostname.replace('*', '.');
                            boolean result = AddHostName(hostname);
                            saveItems();
                            return result;
                        }
                    }else {
                        Toast.makeText(getApplicationContext(),"Enter valid hostname",Toast.LENGTH_SHORT).show();
                    }
                }
                return false;
            }
        });

        myButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if(myEditText.getText().toString().length()>3){
                    String hostname = myEditText.getText().toString();
                    hostname = hostname.replace('*', '.');
                    AddHostName(hostname);
                    saveItems();
                    return;
                }else {
                    Toast.makeText(getApplicationContext(),"Missing hostname",Toast.LENGTH_SHORT).show();
                }

            }
        });

        refreshbutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });

        loadItems();
        txt_mac.setText("Mac Adress : "+wifiOrEthernetMacAdress());
    }

    public void onStart() {
        super.onStart();

        m_background = new Thread(new Runnable() {
            public void run() {
                while(isRunning)
                {
                    for(int i=0; i<items.size(); i++) {
                        PingerItem pi = items.get(i);
                        if(pi.ia!=null) {
//    						pi.result_80 = -1;
//    						pi.result_av = -1;
//    						items.set(i,pi);
                            Thread t1 = new Thread(new Pinger80(pi.ia));
                            t1.setName("Pinger80 " + pi.hostname);
                            t1.start();
                            Thread t2 = new Thread(new PingerAv(pi.ia));
                            t2.setName("PingerAv " + pi.hostname);
                            t2.start();
                        }
                    }

                    try {
                        for(int to=0; to<PERIOD; to+=UPDATE) {
                            Thread.sleep(UPDATE);
                            handler.sendMessage(handler.obtainMessage());
                        }
                    } catch (InterruptedException e) {
                        Log.v("multiping","InterruptedException");
                        break;
                    }
                } // end of while
            }
        });

        isRunning=true;
        m_background.start();
    }

    private void loadItems() {
        // Read host from file
        try {
            InputStream in = openFileInput(SAVEFILE);

            if(in!=null) {
                InputStreamReader tmp=new InputStreamReader(in);
                BufferedReader reader=new BufferedReader(tmp);
                String hostname;
//    			StringBuffer buf=new StringBuffer();
                while ((hostname=reader.readLine()) != null) {
//    				Log.v("multiping","read hostname="+hostname);
                    AddHostName(hostname);
                }
                in.close();
                pia.notifyDataSetChanged();
                myEditText.setText("");
//    			Toast.makeText(this, "saved file loaded", Toast.LENGTH_SHORT).show();
            }
        }
        catch (Throwable t)
        {
            //
        }
    }

    private void saveItems() {
        try {
            OutputStreamWriter out=
                    new OutputStreamWriter(openFileOutput(SAVEFILE, 0));
            for(int i=items.size()-1; i>=0; i--) {
                out.write(items.get(i).hostname + "\n");
            }
            out.close();
        }
        catch (Throwable t) {
            Toast.makeText(this, "Exception: " + t.toString(), Toast.LENGTH_SHORT).show();
        }
    }

    public void onStop() {
        super.onStop();
        //saveItems();
        isRunning= false;
    }
    public void fullScreenActivity(){
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getSupportActionBar().hide();
    }
    public void onPause() {
        super.onPause();
    }

    private void refresh() {
        try {
            OutputStreamWriter out=
                    new OutputStreamWriter(openFileOutput(SAVEFILE, 0));
            for(int i=items.size()-1; i>=0; i--) {
                out.write(items.get(i).hostname + "\n");
            }
            out.close();
        }
        catch (Throwable t) {
            Toast.makeText(this, "Exception: " + t.toString(), Toast.LENGTH_SHORT).show();
        }

        // Restart
        Intent intent = getIntent();
        finish();
        startActivity(intent);
    }

    public void onBackPressed()
    {
        finish();
        return;
    }

}


class ViewWrapper {
    View base;
    TextView hostip=null;
    TextView delay=null;

    ViewWrapper(View base) {
        this.base = base;
    }

    TextView getViewHostIp() {
        if (hostip==null) {
            hostip=(TextView)base.findViewById(R.id.hostip);
        }
        return hostip;
    }

    TextView getViewDelay() {
        if (delay==null) {
            delay=(TextView)base.findViewById(R.id.delay);
        }
        return delay;
    }
}