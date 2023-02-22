<p align="center">
  <a href="https://www.infoobjects.com/" target="blank"><img src="screenshots/logo.png" width="150" alt="InfoObjects Logo" /></a>
</p>
<p align="center">Infoobjects is a consulting company that helps enterprises transform how and where they run applications and infrastructure.
From strategy, to implementation, to ongoing managed services, Infoobjects creates tailored cloud solutions for enterprises at all stages of the cloud journey.</p>

# MYSQL lookup filter plugin for Embulk
[![license](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

​
An Embulk filter plugin for Lookup Transformation with MySQL database
​
## Configuration
​
- **mysql_lookup**: Required attributes for the LookUp Filter Plugin -
    - **filters**:
        - **type**: Name of lookup type (required)
    - **driver_path**: path to the jar file of the MySQL JDBC driver. If not set, the bundled JDBC driver (MySQL Connector/J 8.0.19) will be used (string)
    - **driver_class**: Here we can provide driver class name,if not set,the com.mysql.cj.jdbc.Driver class will be used
    - **host**: database host (example `localhost`) (required)
    - **port**: database port (example port for mssql `1433`) (required
    - **database**: database name (required)
    - **table_name**: table name of your database (required)
    - **username**: username for your database (required)
    - **password**: password for database (required)
    - **mapping_from**: (Name of columns to be matched with table 2 columns) (required)
        - **Name of column-1**: column name-1 from input file
        - **Name of column-2**: column name-2 from input file etc ...
    - **mapping_to**:   (Name of columns to be matched with table 1 columns) (required)
        - **Name of column-1**: column name-1 from input file
        - **Name of column-2**: column name-2 from input file
    - **new_columns**:   (New generated column names) (required)
        - **Name-1,Type-1**: Any Name, Type of the name (name: pin, type: string)
        - **Name-2,Type-2**: Any Name, Type of the name (name: gender, type: string)
        - **Name-3,Type-3**: Any Name, Type of the name (name: phone_number, type: string) etc ...
## Example - columns
​
Input1 for table 1 is as follows :-

```
 ID     Name                Age Address         City        Country Salary
 1      John Doe            25  123 Min St      New York    USA     50000
 2      Jane Doe            30  456 Market Ave  Los Angeles USA     60000   
 3      Jim Smith           35  789 Elm St      Chicago     USA     65000
 4      Sara Lee            40  246 park st     Houseton    USA     70000
 5      Tom Cruise          45  369 Broadway    New York    USA     75000
 6      Brad Pitt           50  159 Market St   Los Angeles USA     80000
 21     Jennifer Aniston    125 753 Main St     Los Angeles USA     155000
 7      Angelina Jolie      55  753 5th Ave     New York    USA     85000
 8      Kate Winslet        60  246 main St     London      UK      90000   
 9      Leonardo DiCaprio   65  369 King St     Los Angeles USA     95000
 10     Cate Blanchett      70  753 Queen St    Sydney      ""      100000
```

Input2 for table 2 is as follows :-

```
ID  Name                Pin     Gender  Phone_Number
1   John Doe            11111   Male    111-111-1111
2   Jane Doe            22222   Female  222-222-2222
3   Jim Smith           33333   Male    333-333-3333
4   Maria Begum         44444   Female  444-444-4444
5   Tom Cruise          55555   Male    555-555-5555
6   Brad Pitt           66666   Female  666-666-6666
7   Angelina Jolie      77777   Male    777-777-7777
8   Kate Winslet        88888   Female  888-888-8888
9   Leonardo Dicaprio   99999   Male    999-999-9999
10  Krrish Jordan       12345   Male    123-456-7890
```

As shown in yaml below, columns mentioned in mapping_from will be mapped with columns mentioned in mapping_to      
ie:


ID : ID                      
Name : Name

After successful mapping an Output.csv file containing the columns mentioned in new_columns will be generated

Output File generated :-

```
ID  Name                Age Address         City        Country Salary  Pin     Gender  Phone_Number
1   John Doe            25  123 Main St     New York    USA     50000   11111   Male    111-111-1111
2   Jane Doe            30  456 Market Ave  Los Angeles USA     60000   22222   Female  222-222-2222
3   Jim Smith           35  789 Elm St      Chicago     USA     65000   33333   Male    333-333-3333 
4   Sara Lee            40  246 Park St     Houston     USA     70000   
5   Tom Cruise          45  369 Broadway    New york    USA     75000   55555   Male    555-555-5555
6   Brad Pitt           50  159 Market St   Los Angeles USA     80000   66666   Female  666-666-6666
21  Jennifer Aniston    125 753 Main St     Los Angeles USA     155000
7   Angelina Jolie      55  753 5th Ave     New York    USA     85000   77777   Male    777-777-7777
8   Kate Winslet        60  246 main St     Landon      UK      90000   88888   Female  888-888-8888
9   Leonardo DiCaprio   65  369 King St     Los Angeles USA     95000   99999   Male    999-999-9999
10  Cate Blanchett      70  753 Queen       Sydney      \\""    100000   
```
​
​
​
```yaml
 - type: mysql_lookup
   host: localhost
   port: 1433
   database: test
   table_name: country_details
   username: root
   password: root
   mapping_from:
   - country_code
   - country_name
   mapping_to:
   - id
   - country_address
   new_columns:
   - { name: country_GDP, type: string }
   - { name: country_population, type: string }
```
​
Notes:
1. mapping_from attribute should be in same order as mentioned in input file.
   ​
2. This attribute needs to be provided(in input plugin) while using jdbc input plugin in case datatype is Number:------
``` 
     column_options: 
     id: {value_type: long}
```
3. Matching columns data types must be int,long and String
## Development
​
Run example:
​
```
$ ./gradlew package
$ embulk run -I ./lib seed.yml
```
​
Deployment Steps:
​
```
Install ruby in your machine
$ gem install gemcutter (For windows OS)
​
$ ./gradlew gemPush
$ gem build NameOfYourPlugins (example: embulk-filter-mssql_lookup)
$ gem push embulk-filter-mssql_lookup-0.1.0.gem (You will get this name after running above command)
```
​
​
Release gem:
​
```
$ ./gradlew gemPush
```
## Licensing

InfoObjects [license](LICENSE) (MIT License)