# TV System Debugging Tool

This Android app provides a line-oriented terminal / console for devices with a serial / UART interface connected with a USB-to-serial-converter.

It supports USB to serial converters based on
- FTDI FT232, FT2232, ...
- Prolific PL2303
- Silabs CP2102, CP2105, ...
- Qinheng CH340, CH341

and devices implementing the USB CDC protocol like
- Arduino using ATmega32U4
- Digispark using V-USB software USB
- BBC micro:bit using ARM mbed DAPLink firmware

## Features

- permission handling on device connection
- foreground service to buffer receive data while the app is rotating, in background, ...

## Credits

The app uses the [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android) library.
The app is base on [SimpleUsbTerminal](https://github.com/kai-morich/SimpleUsbTerminal) app.

