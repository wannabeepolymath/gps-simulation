Strava hacks script collection
===============================

I will uploading here my collections of scripts to get some hacks from Strava.... for python3

## Installing on MacOSX
    sudo easy_install pip
    sudo pip install -r requirements.txt

## Installing on Ubuntu/Debian
    
    sudo apt install python3
    sudo apt install python3-pip
    sudo pip3 install -r requirements.txt
    
### strava_traces_downloader.py

with this script you can reconstruct a gpx file from the public strava activity information. without premium account or any else. 

This script **doesn't download the original GPX file**.  is just some a reconstruction, and is not better than the original GPX File.

Sometimes, When you aren't logged, and the activity is public, we can get 100 points only. Is not bad, but when you are logged and the activities is from a friend, we can get thousands of points (resulting a better GPX file). 


    usage: strava_traces_downloader.py [-h] [-a ID_Number] [-ai IDstart IDend]
                                       [-o output.gpx] [-l username password]
                                       [-nt] [-v]
    
    Download GPS Traces from Strava.
        
    optional arguments:
      -h, --help            show this help message and exit
      -a ID_Number, --activity ID_Number
                            ID of activity to download (default: None)
      -ai IDstart IDend, --activityinterval IDstart IDend
                            A interval of activities. auto outputname, not
                            compatible with output name file option (default:
                            ('None', 'None'))
      -o output.gpx, --output output.gpx
                            name of GPX file output. (default: output.gpx)
      -l username password, --login username password
                            login with username and password (default: ('None',
                            'None'))
      -nt, --notime         download track without time parameters (default:
                            False)
      -v, --verbose         increase output verbosity (default: False)

**EXAMPLES**

Download a simple activity 

    python strava_traces_downloader.py -a activitynumber -l youremail@domain.org yourpassword -o fileoutput.gpx

Download multiple activities

    python strava_traces_downloader.py -ai 427796872 427796999 -l youremail@domain.org yourpassword                   


### join_gpx_strava_files.py

a fast way to join a lot of GPX files downloaded from strava if you use strava_traces_downloader.py , this is your second step for join  all in just one file. 

    python join_gpx_strava_files.py [gpx_directory] [outputfile.gpx]

### strava_kudos_tool.py

This script can help you to give a lot kudos for your friends.
                        
                        
    usage: strava_kudos_tool.py [-h] [-a ID_Number] [-fd UNIX_TIME] -l username password [-fl] [-nebr Number] [-ft FeedType] [-c CLUB_ID] [-v]

    Strava Kudos Tool.

    options:
    -h, --help            show this help message and exit
    -a ID_Number, --athlete ID_Number
                            Filter by ID of Athlete to analyze, by default all feed is analyzed. (default: None)
    -fd UNIX_TIME, --feeddeep UNIX_TIME
                            Force a end of time for get the feed, define a UNIX TIME (default: None)
    -l username password, --login username password
                            Login with username and password (default: ('None', 'None'))
    -fl, --forcelogin     Force a new login instead saved cookies. Warning, this option will destroy old credentials.
                            (default: False)
    -nebr Number, --numentriesbyrequest Number
                            Number of entries asked for each request, by default this value is controlled by Strava. If
                            even set a big number, strava could rewrite it with the max value from server. (100 entries)
                            (default: 0)
    -ft FeedType, --feedtype FeedType
                            Force a filter by feed type, by default this value is controlled by Strava (default: None)
    -c CLUB_ID, --club CLUB_ID
                            Use it with -ft option when 'club' feed type is used. (default: None)
    -y, --yes             yes to all (default: False)
    -v, --verbose         increase output verbosity (default: False)

**EXAMPLES**

Give kudos for all your following feed.

    python strava_kudos_tool.py -l youremail@domain.org yourpassword

Give kudos for all activities in a specific club.

    python strava_kudos_tool.py -l youremail@domain.org yourpassword -ft club -c 12345678

### gpx_add_time.py

Adds time information to a GPX file that has no `<time>` data. You provide a start datetime and an average pace, and the tool assigns timestamps to every trackpoint using cumulative great-circle distance (constant pace, no gradient adjustment).

    usage: gpx_add_time.py [-h] -i INPUT -s START -p PACE [-o OUTPUT] [-v]

    Add time information to a GPX file using a constant pace.

    required arguments:
      -i, --input PATH     Input GPX file (no time data).
      -s, --start ISO_DT   Start datetime, ISO 8601 (e.g. 2024-03-15T07:30:00
                           or 2024-03-15T07:30:00+05:30). Naive values are UTC.
      -p, --pace MM:SS     Average pace, minutes:seconds per km (e.g. 5:30).

    optional arguments:
      -o, --output PATH    Output GPX file. Default: <input-stem>_timed.gpx
      -v, --verbose        Print per-segment stats to stderr.

**EXAMPLES**

Add timestamps to a notime GPX (5:30/km pace, starting 7:30 AM IST):

    python gpx_add_time.py -i ride.gpx -s 2024-03-15T07:30:00+05:30 -p 5:30

### gpx_build.py

Synthesizes a realistic timed GPX from a starting coordinate. Routes via OSRM (`router.project-osrm.org`), looks up elevation via opentopodata, applies a grade-adjusted pace model with per-sample jitter, warmup/cooldown ramps, optional pauses, and small lateral GPS noise. Four input modes:

- **M1** `--end LAT,LON` — route from start to endpoint.
- **M2** `--waypoint LAT,LON` (repeatable) — route through given corners in order.
- **M3** `--loop --distance KM` — auto-generated round trip from start.
- **M4** `--polyline FILE.gpx` — apply timing/elevation/jitter to an imported polyline.

External services have public rate limits — keep usage modest. OSRM failures abort; opentopodata failures warn and fall back to ele=0.

    usage: gpx_build.py [-h] [-s LAT,LON] -t ISO_DT -p MM:SS
                        (--end LAT,LON | --waypoint LAT,LON | --loop | --polyline FILE)
                        [--distance KM] [--profile {foot,bike,car}]
                        [--spacing-hz HZ] [--pace-jitter PCT] [--grade-penalty F]
                        [--gps-noise M] [--warmup-sec N] [--cooldown-sec N]
                        [--pauses N] [--no-elevation] [--elevation-dataset NAME]
                        [--name STRING] [--creator STRING] [--seed N]
                        [-o OUTPUT] [-v]

**EXAMPLES**

Point-to-point 5:30/km run starting 6:30 AM IST:

    python gpx_build.py -s 12.97,77.59 -t 2026-05-25T06:30:00+05:30 -p 5:30 --end 12.98,77.60

5 km auto-loop with reproducible jitter:

    python gpx_build.py -s 12.97,77.59 -t 2026-05-25T06:30:00+05:30 -p 5:30 --loop --distance 5 --seed 42

Apply realistic timing to an existing custom-built route:

    python gpx_build.py -t 2026-05-25T06:30:00+05:30 -p 5:30 --polyline myroute.gpx

### strava_photo_downloader.py

Do you want to download all photos of your friends? no problem. 

    usage: strava_photo_downloader.py [-h] -a ID_Number -l username password [-fl] [-y] [-v]
    
    Strava Photo Downloader Tool.
    
    options:
      -h, --help            show this help message and exit
      -a ID_Number, --athlete ID_Number
                            ID of Athlete to analyze (default: None)
      -l username password, --login username password
                            Login with username and password (default: ('None', 'None'))
      -fl, --forcelogin     Force a new login instead saved cookies. Warning, this option will destroy old credentials. (default: False)
      -y, --yes             Yes to all (default: False)
      -v, --verbose         increase output verbosity (default: False)

**EXAMPLES**

Download all photos of a friend from the feed

    python strava_photo_downloader.py -l youremail@domain.org yourpassword -a 12345678
