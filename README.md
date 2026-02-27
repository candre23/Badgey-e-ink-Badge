# Badgey E-Ink Badge System
A smart badge system using the Heltec Visionmaster E290 board and an android app


### Disclaimer
This is pure vibeslop.  It works on my machine(s), but I make no promises that it
will work for you.  I am unable to provide tech support.  I cannot guarentee that 
this software won't brick your phone, cause your genitals to implode, suddenly 
become sentient and initiate the inevitable machine uprising, or cause any 
conceivable or inconceivable undesired outcome.  Use at your own risk.  

Or don't.  I'm not your mom, and I don't work for OSHA.


### Hardware
The guts of this contraption is the Heltec Visionmaster E290 microcontroller board, 
which you can find out more about here: https://heltec.org/project/vision-master-e290/  
This doohicky is an ESP32S-based board with an integrated 2.9" 296x128 E-ink display. 
There's also a LoRa radio module that I just don't have the give-a-fuck to integrate 
into this project at this time, but as a meshtastic enjoyer, I would like to 
eventually.  Other material components required to construct a complete Badgey are a 
small 1S (3.7v) LiPo battery, a power switch, and some filament to print the case. See 
the BOM.txt for the exact bits I used.


### Assembly
Should be fairly self-explanatory.  Annoyingly, these heltec boards all use an uncommonly-small 
JST connector so practically no off-the-shelf LiPo will fit.  The E290 will come with a battery 
connector whip though.  You'll (carefully, ONE LEG AT A TIME) cut the connector off your LiPo, 
soldering the negative battery wire to the negative connector wire.  The positive from the 
battery gets soldered to one of the outer switch contacts, and the positive connector wire gets 
soldered to the center switch post.  Screw the switch into the case, stick the board in the 
case, cram the battery in there somewhere, and it should look something like this:
![PXL_20260227_204958267](https://github.com/user-attachments/assets/dbeebf4c-2c6c-4650-a415-9d800ce7c829)  
The back is secured with a pair of M3x8mm (or shorter) bolts.


### Badge Operation
You will need to compile and upload the sketch to the badge before it will do anything.  If 
you're not familiar with the process, there's loads of youtube videos.  You will need the 
heltec e-ink module and the nimBLE bluetooth module in order to compile:  
https://github.com/todd-herbert/heltec-eink-modules  
https://h2zero.github.io/NimBLE-Arduino/index.html  
When you power up the badge, it will write the happy Badgey image to the display and wait for 
a new image to be sent from the app.  When the badge is awake and waiting for the app, the 
bright white LED will flash once per second.  After 5min without activity (or once it recieves 
an image) it will go into deep sleep to conserve battery life.  Pressing the user button (the 
middle one) will wake the board up and it will again look for data from the app over bluetooth. 
A quick double-press of the user button will cycle between the current display sent from the 
app, the built-in Badgey image, and a status screen with FW version, battery state, etc.



### The Android App
Install it on your phone and hopefully it works.  I'm not an android dev and I've tested it
on exactly one phone: my pixel 9a.  The app requires permissions for bluetooth (to talk to
the badge) and precise location information (I have no clue why, but it doesn't work without 
it).  When the badge is powered up and awake, hit the Scan button to look for local BLE 
devices. Badgy will show up as "E-Badge" - select that to bind your badge to your phone.  You 
should only have to do this once.  You can add/edit text and image objects to create the badge 
display of your dreams.  Yes, the object editing section is cramped and requires a lot of 
scrolling up and down.  Feel free to make it better if you think you can.  Once you have the 
display configured as you like, hit the Connect button to connect to the badge.  It will need 
to be awake for this.  Once it's connected, you can hit the send button to send your creation 
to the badge.  The badge SHOULD display exactly what you have in the app preview window and 
then go to sleep.  It seems to have about an 80% success rate.  If the display doesn't take, 
just wake the badge up, connect, and send again.  

<img width="1080" height="2201" alt="Screenshot_20260226-215928" src="https://github.com/user-attachments/assets/1677fcc2-593c-4c88-8b18-d60cc6ee3405" />


