#!/bin/bash

# Script to find unused drawable resources in Android project
# This script scans all drawable files and checks if they're referenced in Kotlin or XML files

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Project paths
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RES_DIR="$PROJECT_DIR/app/src/main/res"
KOTLIN_DIR="$PROJECT_DIR/app/src/main/java"
OUTPUT_DIR="$PROJECT_DIR/unused_drawables_report"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")

echo -e "${BLUE}=== Unused Drawable Resources Scanner ===${NC}"
echo -e "${BLUE}Project: $PROJECT_DIR${NC}"
echo ""

# Create output directory
mkdir -p "$OUTPUT_DIR"

# Output files
UNUSED_LIST="$OUTPUT_DIR/unused_drawables_${TIMESTAMP}.txt"
USED_LIST="$OUTPUT_DIR/used_drawables_${TIMESTAMP}.txt"
BACKUP_SCRIPT="$OUTPUT_DIR/backup_unused_${TIMESTAMP}.sh"
DELETE_SCRIPT="$OUTPUT_DIR/delete_unused_${TIMESTAMP}.sh"
SUMMARY_REPORT="$OUTPUT_DIR/summary_${TIMESTAMP}.txt"

# Clear output files
> "$UNUSED_LIST"
> "$USED_LIST"
> "$BACKUP_SCRIPT"
> "$DELETE_SCRIPT"
> "$SUMMARY_REPORT"

echo -e "${YELLOW}Step 1: Collecting all drawable files...${NC}"

# Find all drawable files (excluding directories)
DRAWABLE_FILES=()
while IFS= read -r -d '' file; do
    DRAWABLE_FILES+=("$file")
done < <(find "$RES_DIR" -type f \( -path "*/drawable*/*" -o -path "*/mipmap*/*" \) \( -name "*.xml" -o -name "*.png" -o -name "*.jpg" -o -name "*.jpeg" -o -name "*.webp" -o -name "*.gif" \) -print0)

TOTAL_DRAWABLES=${#DRAWABLE_FILES[@]}
echo -e "${GREEN}Found $TOTAL_DRAWABLES drawable files${NC}"
echo ""

if [ $TOTAL_DRAWABLES -eq 0 ]; then
    echo -e "${RED}No drawable files found!${NC}"
    exit 1
fi

echo -e "${YELLOW}Step 2: Analyzing drawable usage...${NC}"

USED_COUNT=0
UNUSED_COUNT=0

# Create backup script header
cat > "$BACKUP_SCRIPT" << 'EOF'
#!/bin/bash
# Script to backup unused drawable files
BACKUP_DIR="unused_drawables_backup_$(date +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"
echo "Backing up unused drawables to $BACKUP_DIR..."
EOF
chmod +x "$BACKUP_SCRIPT"

# Create delete script header
cat > "$DELETE_SCRIPT" << 'EOF'
#!/bin/bash
# Script to delete unused drawable files
# WARNING: This will permanently delete files. Make sure you have a backup!
echo "This will delete unused drawable files."
read -p "Are you sure you want to continue? (yes/no): " confirm
if [ "$confirm" != "yes" ]; then
    echo "Deletion cancelled."
    exit 0
fi
echo "Deleting unused drawables..."
EOF
chmod +x "$DELETE_SCRIPT"

# Check each drawable file
for drawable_file in "${DRAWABLE_FILES[@]}"; do
    # Get the filename without extension and path
    filename=$(basename "$drawable_file")
    resource_name="${filename%.*}"
    
    # Skip ic_launcher files (app icons)
    if [[ "$resource_name" == ic_launcher* ]]; then
        echo "  [SKIP] $resource_name (app icon)" >> "$USED_LIST"
        ((USED_COUNT++))
        continue
    fi
    
    # Skip adaptive icon files
    if [[ "$drawable_file" == *"/mipmap-"* ]]; then
        echo "  [SKIP] $resource_name (mipmap icon)" >> "$USED_LIST"
        ((USED_COUNT++))
        continue
    fi
    
    IS_USED=false
    
    # Check in Kotlin files for R.drawable.resource_name or R.mipmap.resource_name
    if grep -r -q "R\.drawable\.$resource_name\|R\.mipmap\.$resource_name" "$KOTLIN_DIR" 2>/dev/null; then
        IS_USED=true
    fi
    
    # Check in XML files for @drawable/resource_name or @mipmap/resource_name
    if ! $IS_USED; then
        if grep -r -q "@drawable/$resource_name\|@mipmap/$resource_name" "$RES_DIR" 2>/dev/null; then
            IS_USED=true
        fi
    fi
    
    # Check if it's referenced in AndroidManifest.xml
    if ! $IS_USED; then
        if [ -f "$PROJECT_DIR/app/src/main/AndroidManifest.xml" ]; then
            if grep -q "@drawable/$resource_name\|@mipmap/$resource_name" "$PROJECT_DIR/app/src/main/AndroidManifest.xml" 2>/dev/null; then
                IS_USED=true
            fi
        fi
    fi
    
    # Record results
    if $IS_USED; then
        echo "  [USED] $resource_name" >> "$USED_LIST"
        ((USED_COUNT++))
        echo -ne "${GREEN}.${NC}"
    else
        echo "$drawable_file" >> "$UNUSED_LIST"
        ((UNUSED_COUNT++))
        echo -ne "${RED}.${NC}"
        
        # Add to backup script
        echo "cp \"$drawable_file\" \"\$BACKUP_DIR/\"" >> "$BACKUP_SCRIPT"
        
        # Add to delete script
        echo "rm -f \"$drawable_file\"" >> "$DELETE_SCRIPT"
    fi
done

echo "" # New line after progress dots
echo ""

# Add completion messages to scripts
echo 'echo "Backup complete! Files saved to $BACKUP_DIR"' >> "$BACKUP_SCRIPT"
echo 'echo "Deletion complete!"' >> "$DELETE_SCRIPT"
echo 'echo "You can now run ./gradlew clean build to verify the build still works."' >> "$DELETE_SCRIPT"

# Generate summary report
cat > "$SUMMARY_REPORT" << EOF
=== DRAWABLE USAGE ANALYSIS REPORT ===
Generated: $(date)
Project: $PROJECT_DIR

STATISTICS:
-----------
Total Drawables Found:    $TOTAL_DRAWABLES
Used Drawables:           $USED_COUNT
Unused Drawables:         $UNUSED_COUNT
Usage Rate:               $(awk "BEGIN {printf \"%.1f\", ($USED_COUNT/$TOTAL_DRAWABLES)*100}")%

FILES:
------
Unused drawables list:    $UNUSED_LIST
Used drawables list:      $USED_LIST
Backup script:            $BACKUP_SCRIPT
Delete script:            $DELETE_SCRIPT

NEXT STEPS:
-----------
1. Review the unused drawables list: cat "$UNUSED_LIST"
2. Backup unused files before deletion: $BACKUP_SCRIPT
3. Delete unused files (after backup): $DELETE_SCRIPT
4. Rebuild project: ./gradlew clean build

WARNING: Always backup before deleting files!
EOF

# Display summary
echo -e "${BLUE}=== ANALYSIS COMPLETE ===${NC}"
echo ""
cat "$SUMMARY_REPORT"
echo ""

# Display unused drawables if any
if [ $UNUSED_COUNT -gt 0 ]; then
    echo -e "${YELLOW}Unused drawables (first 20):${NC}"
    head -20 "$UNUSED_LIST" | while read -r file; do
        echo -e "  ${RED}✗${NC} $(basename "$file")"
    done
    
    if [ $UNUSED_COUNT -gt 20 ]; then
        echo -e "  ${YELLOW}... and $((UNUSED_COUNT - 20)) more${NC}"
    fi
    echo ""
    echo -e "${YELLOW}Full list saved to: $UNUSED_LIST${NC}"
else
    echo -e "${GREEN}All drawables are being used! ✓${NC}"
fi

echo ""
echo -e "${BLUE}Reports saved to: $OUTPUT_DIR${NC}"
echo ""
