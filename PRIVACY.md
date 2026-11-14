# Privacy policy

*Last updated: 3 November 2026*

Nocturne is an open-source Android app for controlling a BedJet 3 over Bluetooth. This policy explains what it
does with your information. The short version: **everything stays on your phone, and nothing is sent to us or
anyone else.**

## What Nocturne doesn't do

- It has no internet permission, so it cannot send or receive anything over the internet.
- It has no account, sign-in, server or cloud storage.
- It contains no analytics, advertising, tracking or crash-reporting libraries. If it crashes, it keeps the last
  crash trace on your phone only (see below).
- It does not sell, share or transfer any data. The developer never receives any of it.

## What it keeps on your phone

Stored in Nocturne's private app storage, which Android keeps away from other apps:

- your night profiles, presets, widget and app settings;
- the BedJet you chose (its Bluetooth address and name) and what was in the BedJet programme slot before Nocturne
  first used it;
- a history of nights the BedJet ran (times, modes, temperatures and changes), kept for the period you choose in
  Settings (7, 30, 90 or 365 days), and an in-app log of the notifications it showed;
- automatic and manual backup snapshots.

Uninstalling Nocturne deletes all of this. You can also delete sleep history in Settings at any time.

## Information it reads

- **Bluetooth**: it scans for and connects to your BedJet. Scanning is declared as not used to work out your
  location, and Nocturne doesn't record other nearby devices.
- **Your next alarm**: it reads the time of the next alarm, and which app set it, from Android so it can plan the
  night. It doesn't read or change your alarms in the clock app. It keeps a short list of the apps Android has named
  as setting your next alarm, so it can offer them in Settings; that list stays on the phone and isn't backed up.
- **Names and icons of your apps**: to let you choose which apps' alarms count, Nocturne can see the apps on your
  home screen. It uses this only to show an app's name and icon and whether the app asks to set alarms. It doesn't
  read anything from those apps, and nothing leaves the phone.
- **Health Connect (optional, off by default)**: if you turn it on and grant permission, Nocturne reads sleep
  sessions and stages to show them on the Sleep page beside your BedJet nights. It only reads: it never writes or
  changes anything in Health Connect. You can withdraw the permission at any time. (Betas before 1.1.0-beta.4 could
  also write finished BedJet programmes there; anything already written stays in Health Connect until you remove it in
  the Health Connect app.)
- **Companion device presence (optional)**: if you allow it, Android tells Nocturne when your BedJet is in range so
  the widget can reconnect. This is handled by Android and nothing is recorded beyond the setting.
- **The last crash**: if Nocturne crashes, it saves the error's technical trace (the time, the app version and the
  code that failed) in its own storage, replacing any earlier one. It contains no personal data, isn't backed up, and
  only leaves the phone inside a diagnostics file you save yourself.

## Files you export

Backups, history exports and diagnostics are only written when you ask, to a location you pick. Once saved, those
files are under your control. A diagnostics file contains the phone model, app settings (including the chosen
BedJet), tonight's state, your next alarm time and which app set it, recent night events and notices, and the last
crash trace if there is one. It contains no Health Connect data. Nothing is sent anywhere unless you share the file yourself.

## Children

Nocturne isn't directed at children and doesn't knowingly collect anything from anyone.

## Changes and contact

Any change to this policy will be made in this file in the
[GitHub repository](https://github.com/Willzy12h/bedjet-android), where you can also open an issue with questions.
