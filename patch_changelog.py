import re
from datetime import datetime

with open('CHANGELOG.md', 'r', encoding='utf-8') as f:
    content = f.read()

date_str = datetime.now().strftime('%Y-%m-%d')
new_entry = f"""## [1.5.5] - {date_str}
### 🐛 Sửa lỗi
- **Làm mới Script**: Khắc phục lỗi vuốt để làm mới (pull-to-refresh) ở tab Scripts không cập nhật danh sách script. Đã sửa để hệ thống tự động tải lại cả script từ server và script cục bộ thay vì tải lại danh sách ứng dụng.

"""

# Insert after "# Changelog\n\n"
content = content.replace('# Changelog\n\n', f'# Changelog\n\n{new_entry}')

with open('CHANGELOG.md', 'w', encoding='utf-8') as f:
    f.write(content)
