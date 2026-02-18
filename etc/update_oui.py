#!/usr/bin/env python3
"""
Update OUI (Organizationally Unique Identifier) database for WiGLE WiFi Wardriving.

Downloads IEEE OUI registration files and generates oui.properties for MAC address
manufacturer lookup.

Data sources:
  - OUI-24 (MA-L): https://standards-oui.ieee.org/oui/oui.txt
  - OUI-28 (MA-M): https://standards-oui.ieee.org/oui28/mam.txt
  - OUI-36 (MA-S): https://standards-oui.ieee.org/oui36/oui36.txt

Usage:
  python update_oui.py

Output:
  ../wiglewifiwardriving/src/main/assets/oui.properties
"""

import os
import re
import sys
import urllib.request

# URLs for IEEE OUI data
OUI_SOURCES = {
    'oui.txt': 'https://standards-oui.ieee.org/oui/oui.txt',      # OUI-24 (MA-L)
    'mam.txt': 'https://standards-oui.ieee.org/oui28/mam.txt',    # OUI-28 (MA-M)
    'oui36.txt': 'https://standards-oui.ieee.org/oui36/oui36.txt' # OUI-36 (MA-S)
}

# Output path relative to script location
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT_FILE = os.path.join(SCRIPT_DIR, '..', 'wiglewifiwardriving', 'src', 'main', 'assets', 'oui.properties')


def download_file(url, filename):
    """Download a file from URL with browser-like headers."""
    filepath = os.path.join(SCRIPT_DIR, filename)
    print(f"Downloading {filename} from {url}...")
    
    req = urllib.request.Request(url, headers={
        'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
    })
    
    try:
        with urllib.request.urlopen(req, timeout=60) as response:
            with open(filepath, 'wb') as f:
                f.write(response.read())
        size_mb = os.path.getsize(filepath) / (1024 * 1024)
        print(f"  Downloaded: {size_mb:.2f} MB")
        return filepath
    except Exception as e:
        print(f"  Error downloading {filename}: {e}")
        return None


def parse_oui24(filepath):
    """Parse OUI-24 (MA-L) entries from oui.txt - 6 character prefixes."""
    oui_map = {}
    
    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            # Match: 286FB9     (base 16)            Nokia Shanghai Bell Co., Ltd.
            match = re.match(r'^([0-9A-Fa-f]{6})\s+\(base 16\)\s+(.+)$', line.strip())
            if match:
                prefix = match.group(1).upper()
                company = match.group(2).strip()
                oui_map[prefix] = company
    
    return oui_map


def parse_oui28(filepath):
    """Parse OUI-28 (MA-M) entries from mam.txt - 7 character prefixes."""
    oui_map = {}
    current_oui = None
    
    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            # Get base OUI from hex line: C8-5C-E2   (hex)
            hex_match = re.match(r'^([0-9A-Fa-f]{2})-([0-9A-Fa-f]{2})-([0-9A-Fa-f]{2})\s+\(hex\)', line.strip())
            if hex_match:
                current_oui = (hex_match.group(1) + hex_match.group(2) + hex_match.group(3)).upper()
            
            # Get MAM assignment: 700000-7FFFFF     (base 16)             Company Name
            mam_match = re.match(r'^([0-9A-Fa-f])00000-[0-9A-Fa-f]FFFFF\s+\(base 16\)\s+(.+)$', line.strip())
            if mam_match and current_oui:
                nibble = mam_match.group(1).upper()
                company = mam_match.group(2).strip()
                prefix = current_oui + nibble  # 7 characters
                oui_map[prefix] = company
    
    return oui_map


def parse_oui36(filepath):
    """Parse OUI-36 (MA-S) entries from oui36.txt - 9 character prefixes."""
    oui_map = {}
    current_oui = None
    
    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            # Get base OUI from hex line
            hex_match = re.match(r'^([0-9A-Fa-f]{2})-([0-9A-Fa-f]{2})-([0-9A-Fa-f]{2})\s+\(hex\)', line.strip())
            if hex_match:
                current_oui = (hex_match.group(1) + hex_match.group(2) + hex_match.group(3)).upper()
            
            # Get OUI-36 assignment: F00000-FFFFFF or similar pattern
            oui36_match = re.match(r'^([0-9A-Fa-f]{3})000-[0-9A-Fa-f]{3}FFF\s+\(base 16\)\s+(.+)$', line.strip())
            if oui36_match and current_oui:
                prefix_ext = oui36_match.group(1).upper()
                company = oui36_match.group(2).strip()
                prefix = current_oui + prefix_ext  # 9 characters
                oui_map[prefix] = company
    
    return oui_map


# Words/abbreviations that should stay uppercase
UPPERCASE_WORDS = {
    'LLC', 'LTD', 'INC', 'CO', 'CORP', 'AG', 'SA', 'NV', 'BV', 'AB', 'AS', 'OY',
    'GMBH', 'KG', 'OHG', 'SE', 'PLC', 'LP', 'LLP', 'SRL', 'SPA', 'SARL', 'EURL',
    'USA', 'UK', 'EU', 'US', 'IEEE', 'IT', 'AI', 'IoT', 'IOT', 'IP', 'TV', 'PC',
    'ID', 'RF', 'LED', 'LCD', 'USB', 'GPS', 'WiFi', 'WIFI', 'LAN', 'WAN', 'VPN',
    'API', 'SDK', 'OEM', 'ODM', 'R&D', 'ICT', 'ISP', 'ASP', 'SaaS', 'PaaS', 'IaaS',
    'CPU', 'GPU', 'RAM', 'ROM', 'SSD', 'HDD', 'NFC', 'RFID', 'HDMI', 'DVI', 'VGA',
    'AC', 'DC', 'AM', 'FM', 'HD', 'SD', 'CD', 'DVD', 'BD', 'AV', '2D', '3D', '4K',
    'BMC', 'OOB', 'IPMI', 'KVM', 'PDU', 'UPS', 'HVAC', 'BMS', 'EMS', 'POS', 'ATM',
    'M2M', 'B2B', 'B2C', 'C2C', 'P2P', 'IoE', 'LPWAN', 'LoRa', 'LORA', 'LTE', '5G',
    'II', 'III', 'IV', 'VI', 'VII', 'VIII', 'IX', 'XI', 'XII', 'XIV', 'XV', 'XVI',
}

# Words that should stay lowercase (unless at start)
LOWERCASE_WORDS = {'a', 'an', 'the', 'and', 'or', 'of', 'for', 'to', 'in', 'on', 'at', 'by', 'de', 'van', 'von', 'la', 'le', 'el', 'di', 'da'}


def title_case_word(word, is_first=False):
    """Convert a word to appropriate case."""
    # Check if word is all caps abbreviation
    word_upper = word.upper().rstrip('.,;')
    if word_upper in UPPERCASE_WORDS:
        return word.upper()
    
    # Check if word should be lowercase
    word_lower = word.lower()
    if not is_first and word_lower in LOWERCASE_WORDS:
        return word_lower
    
    # Handle hyphenated words
    if '-' in word:
        parts = word.split('-')
        return '-'.join(title_case_word(p, i == 0 and is_first) for i, p in enumerate(parts))
    
    # Handle words with apostrophes (e.g., O'Brien)
    if "'" in word:
        parts = word.split("'")
        return "'".join(p.capitalize() for p in parts)
    
    # Default: capitalize first letter
    return word.capitalize()


def to_title_case(name):
    """Convert ALL CAPS name to Title Case while preserving acronyms."""
    # Don't modify if it's not mostly uppercase
    if not name.isupper():
        return name
    
    words = name.split()
    result = []
    for i, word in enumerate(words):
        result.append(title_case_word(word, is_first=(i == 0)))
    
    return ' '.join(result)


def clean_company_name(name):
    """Clean up manufacturer name - fix HTML entities, whitespace, and casing."""
    # Replace &quot; with actual quote
    name = name.replace('&quot;', '"')
    # Replace multiple spaces with single space
    name = re.sub(r'  +', ' ', name)
    # Convert to title case
    name = to_title_case(name)
    return name.strip()


def write_properties(oui_map, output_path):
    """Write OUI map to Java properties file format."""
    # Ensure output directory exists
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    
    with open(output_path, 'w', encoding='utf-8') as f:
        for prefix in sorted(oui_map.keys()):
            company = oui_map[prefix]
            # Clean up company name
            company = clean_company_name(company)
            # Escape special characters for properties format
            company = company.replace('\\', '\\\\')
            company = company.replace('=', '\\=')
            company = company.replace(':', '\\:')
            f.write(f'{prefix}={company}\n')
    
    print(f"Written {len(oui_map)} entries to {output_path}")


def main():
    print("=" * 60)
    print("OUI Database Updater for WiGLE WiFi Wardriving")
    print("=" * 60)
    print()
    
    # Download all source files
    downloaded = {}
    for filename, url in OUI_SOURCES.items():
        filepath = download_file(url, filename)
        if filepath:
            downloaded[filename] = filepath
    
    if not downloaded:
        print("Error: No files downloaded successfully")
        sys.exit(1)
    
    print()
    print("Parsing OUI data...")
    
    oui_map = {}
    
    # Parse OUI-24 (6 char)
    if 'oui.txt' in downloaded:
        oui24 = parse_oui24(downloaded['oui.txt'])
        oui_map.update(oui24)
        print(f"  OUI-24 (6-char): {len(oui24)} entries")
    
    # Parse OUI-28 (7 char)
    if 'mam.txt' in downloaded:
        oui28 = parse_oui28(downloaded['mam.txt'])
        oui_map.update(oui28)
        print(f"  OUI-28 (7-char): {len(oui28)} entries")
    
    # Parse OUI-36 (9 char)
    if 'oui36.txt' in downloaded:
        oui36 = parse_oui36(downloaded['oui36.txt'])
        oui_map.update(oui36)
        print(f"  OUI-36 (9-char): {len(oui36)} entries")
    
    print(f"  Total: {len(oui_map)} entries")
    print()
    
    # Write output
    output_path = os.path.normpath(OUTPUT_FILE)
    write_properties(oui_map, output_path)
    
    # Clean up downloaded files
    print()
    print("Cleaning up...")
    for filename in downloaded:
        try:
            os.remove(downloaded[filename])
        except:
            pass
    
    print()
    print("Done!")


if __name__ == '__main__':
    main()
